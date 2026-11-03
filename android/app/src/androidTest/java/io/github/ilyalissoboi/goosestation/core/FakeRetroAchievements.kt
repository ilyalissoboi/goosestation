// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A stand-in for RetroAchievements' server on the device: it answers `login2` with a password or token, and, for the
 * signed-in user, a game ([GAME_ID]) with one achievement ([ACHIEVEMENT_ID]) and one leaderboard ([LEADERBOARD_ID])
 * for any hash, with its session, unlocks and entries. Its user, password and token are made up ([USER], [PASSWORD],
 * [TOKEN]); no test uses real credentials. Point the core at [url] with [NativeLibrary.setAchievementsServerForTest].
 */
class FakeRetroAchievements : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    val url = "http://127.0.0.1:${server.localPort}"

    /** Each request's form fields, in order. */
    val requests = CopyOnWriteArrayList<Map<String, String>>()

    /** Closes each connection without an answer, as an unreachable server would look. */
    @Volatile var unreachable = false

    /** Answers a token sign-in as the server does for an expired or revoked token. */
    @Volatile var rejectTokens = false

    /** The hashes `gameid` knows, as [GAME_ID]; any other is unknown (0). */
    val hashes = CopyOnWriteArrayList(listOf(GAME_HASH))

    /** Achievements the player already has: their unlocks are answered as the server answers a repeat. */
    val alreadyUnlocked = CopyOnWriteArrayList<Int>()

    /** Answers unlocks with an outage page (503), as a server down for maintenance does. */
    @Volatile var busy = false

    /** Achievements the server refuses, as it does one that no longer exists. */
    val refused = CopyOnWriteArrayList<Int>()

    /** The `awardachievement` requests' fields, in order. */
    fun awards(): List<Map<String, String>> = requests.filter { it["r"] == "awardachievement" }

    /** The `submitlbentry` requests' fields, in order. */
    fun entries(): List<Map<String, String>> = requests.filter { it["r"] == "submitlbentry" }

    private val acceptor =
        thread(isDaemon = true, name = "FakeRetroAchievements") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use(::answer) }
            }
        }

    override fun close() {
        server.close()
        acceptor.join(1000)
    }

    private fun answer(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        val length =
            Regex("(?i)content-length: *(\\d+)")
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) read += input.read(body, read, length - read).also { if (it < 0) return }
        val query =
            head
                .lineSequence()
                .first()
                .split(' ')
                .getOrNull(1)
                ?.substringAfter('?', "")
                .orEmpty()
        val fields = form(query) + form(body.decodeToString())
        requests += fields
        if (unreachable) return

        val (status, json) = reply(fields)
        val bytes = json.encodeToByteArray()
        val out = socket.getOutputStream()
        out.write(
            (
                "HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            ).encodeToByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun reply(fields: Map<String, String>): Pair<Int, String> {
        if (fields["r"] == "login2") {
            if (fields["u"] == USER && (fields["p"] == PASSWORD || (fields["t"] == TOKEN && !rejectTokens))) {
                return 200 to
                    """{"Success":true,"User":"$USER","DisplayName":"Goose","Token":"$TOKEN","Score":300,""" +
                    """"SoftcoreScore":940,"Messages":0}"""
            }
            return rejected(fields)
        }
        if (fields["t"] != TOKEN || rejectTokens) return rejected(fields)

        return when (fields["r"]) {
            "achievementsets" -> 200 to GAME_SETS
            "gameid" -> 200 to """{"Success":true,"GameID":${if (fields["m"] in hashes) GAME_ID else 0}}"""
            "startsession" -> 200 to """{"Success":true,"HardcoreUnlocks":[],"Unlocks":[],"ServerNow":${now()}}"""
            "ping" -> 200 to """{"Success":true}"""
            "awardachievement" ->
                if (busy) {
                    503 to "<html><body>Down for maintenance</body></html>"
                } else {
                    award(fields["a"]?.toIntOrNull() ?: 0)
                }
            "submitlbentry" ->
                200 to
                    """{"Success":true,"Response":{"Score":${fields["s"]},"BestScore":${fields["s"]},""" +
                    """"RankInfo":{"Rank":1,"NumEntries":"1"},"TopEntries":[],"TopEntriesFriends":[],""" +
                    """"LBData":{"Format":"VALUE","LeaderboardID":$LEADERBOARD_ID,"GameID":$GAME_ID,""" +
                    """"Title":"Fastest lap","LowerIsBetter":0}}}"""
            else -> 404 to """{"Success":false,"Error":"Unknown request","Code":"not_found"}"""
        }
    }

    private fun award(id: Int): Pair<Int, String> {
        if (id in refused) return 404 to """{"Success":false,"Error":"Achievement not found","Code":"not_found"}"""
        val repeat = if (id in alreadyUnlocked) """"Error":"User already has this achievement unlocked.",""" else ""
        return 200 to
            """{"Success":true,$repeat"Score":305,"SoftcoreScore":945,"AchievementID":$id,"AchievementsRemaining":0}"""
    }

    private fun rejected(fields: Map<String, String>): Pair<Int, String> {
        val code = if (fields["t"] != null) "expired_token" else "invalid_credentials"
        val error = if (fields["t"] != null) "The access token has expired." else "Invalid username/password."
        return 401 to """{"Success":false,"Error":"$error","Code":"$code"}"""
    }

    private fun now() = System.currentTimeMillis() / 1000

    private fun form(text: String): Map<String, String> =
        text
            .split('&')
            .filter { '=' in it }
            .associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }

    companion object {
        const val USER = "goose"
        const val PASSWORD = "not-a-real-password"
        const val TOKEN = "fake-token-1"

        /** The hash a test gives padtest (NativeLibrary.setAchievementsGameHashForTest), which is this game. */
        const val GAME_HASH = "0123456789abcdef0123456789abcdef"
        const val GAME_ID = 3001
        const val ACHIEVEMENT_ID = 101
        const val ACHIEVEMENT_TITLE = "Ace Pilot"
        const val LEADERBOARD_ID = 201

        private val REASONS = mapOf(200 to "OK", 401 to "Unauthorized", 404 to "Not Found")

        /**
         * The game's achievementsets answer: one core set. The achievement is true after 300 frames, so it isn't
         * already true (waiting) when the set loads; the leaderboard starts after 60 frames and is submitted 120
         * frames later (in hardcore only).
         */
        val GAME_SETS =
            """{"Success":true,"GameId":$GAME_ID,"Title":"Pad Test","ConsoleId":12,"ImageIconUrl":"",""" +
                """"RichPresenceGameId":$GAME_ID,"RichPresencePatch":"","Sets":[{"AchievementSetId":4001,""" +
                """"GameId":$GAME_ID,"Title":"Pad Test","Type":"core","ImageIconUrl":"","Achievements":[{""" +
                """"ID":$ACHIEVEMENT_ID,"Title":"$ACHIEVEMENT_TITLE","Description":"Keep playing","Flags":3,""" +
                """"Points":5,"MemAddr":"0xH0000=0xH0000.300.","Author":"goose","BadgeName":"00000",""" +
                """"Created":1700000000,"Modified":1700000000,"Type":"","Rarity":100,"RarityHardcore":100,""" +
                """"BadgeURL":"","BadgeLockedURL":""}],"Leaderboards":[{"ID":$LEADERBOARD_ID,""" +
                """"Title":"Fastest lap","Description":"Finish",""" +
                """"Mem":"STA:0xH0000=0xH0000.60.::CAN:0=1::SUB:0xH0000=0xH0000.120.::VAL:0xH0000",""" +
                """"Format":"VALUE","LowerIsBetter":false,"Hidden":false}]}]}"""
    }
}
