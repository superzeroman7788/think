package com.thinkandact.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun createHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(json) }
    install(WebSockets)
    // 防「点了卡死」：连接/读取卡住时超时失败，交给上层报错+重试，而不是无限转圈。
    // 不设 requestTimeoutMillis（默认无限），避免掐断实时 ASR 的长连 WebSocket。
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 40_000
    }
}
