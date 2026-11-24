#ifndef GHOSTLOCK_PROFILE_ENTRY_H
#define GHOSTLOCK_PROFILE_ENTRY_H

#include <cstddef>
#include <cstdint>

#include "profile/json_profile.h"
#include "profile/model.h"

namespace ghostlock::profile_entry {
    using component_ids = ghostlock::json_profile::component_ids;

    /* Reads the app-rendered JSON document from stdin (EOF-terminated, 1 MiB
     * cap) and decodes it into the transport struct. `ids`, when given,
     * receives the component selection the document implies. */
    int32_t read_profile_stdin(ghostlock::profile::kernel_offsets *out,
                               char *release_buf, size_t release_buf_cap,
                               component_ids *ids = nullptr);

    /* Reads a length-prefixed document from stdin (4-byte big-endian length +
     * payload). Unlike read_profile_stdin it does not consume to EOF, so stdin
     * stays usable for the status-record ACK channel. */
    int32_t read_profile_frame_stdin(ghostlock::profile::kernel_offsets *out,
                                     char *release_buf, size_t release_buf_cap,
                                     component_ids *ids = nullptr);

    /* Reads a document from a file path. */
    int32_t read_profile_file(const char *path,
                              struct ghostlock::profile::kernel_offsets *out,
                              char *release_buf, size_t release_buf_cap,
                              component_ids *ids = nullptr);
} // namespace ghostlock::profile_entry

#endif
