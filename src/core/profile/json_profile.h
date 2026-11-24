#ifndef GHOSTLOCK_PROFILE_JSON_PROFILE_H
#define GHOSTLOCK_PROFILE_JSON_PROFILE_H

#include <cstddef>
#include <cstdint>
#include <string_view>

#include "profile/model.h"

namespace ghostlock::json_profile {
    /* The component selection the app's document implies. The document names
     * an engine and a syscall; the frontend/backend are this build's shipped
     * pair, and the middleware is the selected syscall's route. */
    struct component_ids {
        uint16_t frontend;
        uint16_t backend;
        uint16_t middleware;
    };

    /* Decodes the app-rendered document into the transport struct.
     *
     * Reads `release`, `schema_version`, the optional run keys `mode` and
     * `safe_mode`, `devices` (at most one entry), `kernel`, and `ghostlock`.
     * `timer` is another backend's block and is not validated here.
     *
     * Returns 0, or -1 with one message in `err`. */
    int32_t parse(std::string_view document, profile::kernel_offsets *out,
                  char *release_buf, size_t release_buf_cap, component_ids *ids,
                  char *err, size_t errlen);
} // namespace ghostlock::json_profile

#endif
