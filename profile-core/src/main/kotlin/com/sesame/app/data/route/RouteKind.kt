package com.sesame.app.data.route

/**
 * The four native syscall families. `token` is the document's spelling; `wire`
 * is the historical GLK1 id, kept only for the cross-language catalog test and
 * old notes — the v3 document and the native JSON reader use the token.
 */
enum class RouteKind(val wire: UInt, val token: String) {
    TCP_ZEROCOPY(1u, "tcp_zerocopy"),
    SELECT_STACK(2u, "select_stack"),
    MULTICAST_WAITER(3u, "multicast_waiter"),
    SIGRETURN(4u, "sigreturn"),
}
