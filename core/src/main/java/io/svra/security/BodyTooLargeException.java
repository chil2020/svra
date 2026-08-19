package io.svra.security;

import java.io.IOException;

/** Request body 超過緩衝上限。由 {@link CachedBodyFilter} 轉成 413。 */
class BodyTooLargeException extends IOException {

    BodyTooLargeException(int maxBytes) {
        super("request body 超過 " + maxBytes + " bytes 的緩衝上限");
    }
}
