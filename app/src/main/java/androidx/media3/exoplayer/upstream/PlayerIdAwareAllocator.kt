package androidx.media3.exoplayer.upstream

import androidx.annotation.RestrictTo
import androidx.media3.exoplayer.analytics.PlayerId

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface PlayerIdAwareAllocator : Allocator {
    fun setPlayerId(playerId: PlayerId)
}
