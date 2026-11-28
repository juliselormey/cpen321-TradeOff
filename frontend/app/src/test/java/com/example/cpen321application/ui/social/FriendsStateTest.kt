package com.example.cpen321application.ui.social

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class FriendsStateTest {
    private fun api(send: () -> Response<SendFriendResponse>): FriendsApi = Proxy.newProxyInstance(
        FriendsApi::class.java.classLoader, arrayOf(FriendsApi::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "me" -> Response.success(MeResponse(FriendProfile("me", "Me", null, "my_username")))
            "friends" -> Response.success(FriendsResponse(emptyList()))
            "requests" -> Response.success(RequestsResponse(emptyList(), emptyList()))
            "send" -> send()
            else -> error(method.name)
        }
    } as FriendsApi

    @Test fun selfRequestDoesNotCallServerOrLoseLoadedProfile() = runBlocking {
        var sent = 0
        val state = FriendsState(api { sent++; error("Must not send") }) { "token" }
        state.refresh()
        assertTrue(state.loaded)
        assertFalse(state.send("MY_USERNAME"))
        assertEquals(0, sent)
        assertEquals("my_username", state.me?.username)
        assertFalse(state.busy)
    }

    @Test fun serverConflictIsReadableAndUnlocksTheForm() = runBlocking {
        val state = FriendsState(api { Response.error(409, "{}".toResponseBody()) }) { "token" }
        state.refresh()
        assertFalse(state.send("other_user"))
        assertTrue(state.error!!.contains("already friends"))
        assertFalse(state.busy)
    }

    @Test fun missingSessionIsReportedWithoutMakingRequests() = runBlocking {
        val state = FriendsState(api { error("Must not send") }) { null }
        state.refresh()
        assertFalse(state.loaded)
        assertFalse(state.busy)
        assertTrue(state.error!!.contains("session expired"))
    }
}
