package com.example.cpen321application.ui.social

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class LeaderboardLoaderTest {
    private class FakeApi(private val result: suspend () -> Response<LeaderboardResponse>) : FriendsApi {
        override suspend fun leaderboard(auth: String) = result()
        override suspend fun me(auth: String): Response<MeResponse> = error("unused")
        override suspend fun friends(auth: String): Response<FriendsResponse> = error("unused")
        override suspend fun requests(auth: String): Response<RequestsResponse> = error("unused")
        override suspend fun send(auth: String, body: AddFriendRequest): Response<SendFriendResponse> = error("unused")
        override suspend fun accept(auth: String, id: String): Response<Unit> = error("unused")
        override suspend fun deleteRequest(auth: String, id: String): Response<Unit> = error("unused")
        override suspend fun remove(auth: String, id: String): Response<Unit> = error("unused")
    }
    @Test fun successEndsLoadingAndDisplaysFriends() = runBlocking {
        val entries = listOf(LeaderboardEntry(FriendProfile("friend", "Friend", null), false, "10000", 0.0))
        val loader = LeaderboardLoader(FakeApi { Response.success(LeaderboardResponse(entries)) }, { "token" })
        loader.refresh()
        assertFalse(loader.state.value.loading)
        assertTrue(loader.state.value.loaded)
        assertEquals(entries, loader.state.value.entries)
    }
    @Test fun hangingRequestTimesOutAndCanRetry() = runBlocking {
        var hang = true
        val loader = LeaderboardLoader(FakeApi {
            if (hang) delay(10_000)
            Response.success(LeaderboardResponse(emptyList()))
        }, { "token" }, timeoutMs = 30)
        loader.refresh()
        assertFalse(loader.state.value.loading)
        assertTrue(loader.state.value.error!!.contains("timed out"))
        hang = false
        loader.refresh()
        assertTrue(loader.state.value.loaded)
        assertNull(loader.state.value.error)
    }
    @Test fun networkFailureEndsLoading() = runBlocking {
        val loader = LeaderboardLoader(FakeApi { throw java.io.IOException() }, { "token" })
        loader.refresh()
        assertFalse(loader.state.value.loading)
        assertNotNull(loader.state.value.error)
    }
    @Test fun missingSessionDoesNotSendRequest() = runBlocking {
        val loader = LeaderboardLoader(FakeApi { error("Should not request") }, { null })
        loader.refresh()
        assertFalse(loader.state.value.loading)
        assertTrue(loader.state.value.error!!.contains("session expired"))
    }
}
