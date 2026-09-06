package com.aegis.vpnclient.vpn

import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.aegis.vpnclient.network.ConfigEntry
import com.aegis.vpnclient.network.PackageInfo
import java.net.URLDecoder

/** Local SOCKS port Xray listens on; a tun2socks bridge feeds the VpnService's TUN fd into this. */
const val LOCAL_SOCKS_PORT = 10808

/**
 * Turns one selected share link into a full Xray-core JSON config, wired so:
 *  - all captured (tunneled) traffic goes out through the chosen server
 *    ("proxy" outbound) by default
 *  - if the package is domain-restricted (allowedDomains non-empty),
 *    everything NOT matching those domains is blocked instead of proxied
 *  - the backend's own apiHost ALWAYS resolves via a direct ("direct"
 *    outbound, not the proxy, not blocked) rule, so the app's own
 *    login/verify calls work even on a domain-locked plan
 */
object XrayConfigBuilder {

    fun build(entry: ConfigEntry, pkg: PackageInfo, apiHost: String): String {
        val root = JsonObject()
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })

        // ── Inbound: local SOCKS that the tun2socks bridge forwards TUN packets into ──
        val inbound = JsonObject().apply {
            addProperty("tag", "socks-in")
            addProperty("listen", "127.0.0.1")
            addProperty("port", LOCAL_SOCKS_PORT)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("udp", true)
                addProperty("auth", "noauth")
            })
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply { add("http"); add("tls") })
            })
        }
        root.add("inbounds", JsonArray().apply { add(inbound) })

        // ── Outbounds: proxy (the actual VPN server), direct (bypass), block ──
        val proxyOutbound = parseLinkToOutbound(entry)
        val directOutbound = JsonObject().apply { addProperty("protocol", "freedom"); addProperty("tag", "direct") }
        val blockOutbound = JsonObject().apply { addProperty("protocol", "blackhole"); addProperty("tag", "block") }
        root.add("outbounds", JsonArray().apply { add(proxyOutbound); add(directOutbound); add(blockOutbound) })

        // ── Routing ──
        val rules = JsonArray()

        // Backend API host: always direct, never blocked/restricted, so login/verify
        // keep working no matter what the package's domain policy is.
        rules.add(JsonObject().apply {
            addProperty("type", "field")
            add("domain", JsonArray().apply { add("full:$apiHost") })
            addProperty("outboundTag", "direct")
        })

        val restricted = pkg.allowedDomains.isNotEmpty()
        if (restricted) {
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("domain", JsonArray().apply { pkg.allowedDomains.forEach { add(it) } })
                addProperty("outboundTag", "proxy")
            })
            // Default: anything not explicitly allowed is blocked outright for a
            // domain-restricted plan (e.g. a "YouTube only" trial tier).
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("network", JsonArray().apply { add("tcp"); add("udp") })
                addProperty("outboundTag", "block")
                addProperty("port", "0-65535")
            })
        }
        // Unrestricted plans: no extra rule needed — Xray's default outbound
        // (first one listed, "proxy") handles everything else already.

        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            add("rules", rules)
        })

        return root.toString()
    }

    private fun parseLinkToOutbound(entry: ConfigEntry): JsonObject {
        return when (entry.protocol) {
            "vless" -> buildVless(entry.link)
            "trojan" -> buildTrojan(entry.link)
            "vmess" -> buildVmess(entry.link)
            "shadowsocks" -> buildShadowsocks(entry.link)
            else -> throw IllegalArgumentException("Unsupported protocol: ${entry.protocol}")
        }
    }

    private fun buildVless(link: String): JsonObject {
        val uri = Uri.parse(link)
        val id = uri.userInfo ?: throw IllegalArgumentException("Malformed vless link")
        val host = uri.host!!
        val port = uri.port
        val security = uri.getQueryParameter("security") ?: "none"
        val network = uri.getQueryParameter("type") ?: "tcp"
        val flow = uri.getQueryParameter("flow")

        val user = JsonObject().apply {
            addProperty("id", id)
            addProperty("encryption", "none")
            if (!flow.isNullOrEmpty()) addProperty("flow", flow)
        }
        val vnext = JsonObject().apply {
            addProperty("address", host)
            addProperty("port", port)
            add("users", JsonArray().apply { add(user) })
        }
        val settings = JsonObject().apply { add("vnext", JsonArray().apply { add(vnext) }) }

        val stream = buildStreamSettings(network, security, uri)

        return JsonObject().apply {
            addProperty("protocol", "vless")
            addProperty("tag", "proxy")
            add("settings", settings)
            add("streamSettings", stream)
        }
    }

    private fun buildTrojan(link: String): JsonObject {
        val uri = Uri.parse(link)
        val password = uri.userInfo ?: throw IllegalArgumentException("Malformed trojan link")
        val host = uri.host!!
        val port = uri.port
        val security = uri.getQueryParameter("security") ?: "tls"
        val network = uri.getQueryParameter("type") ?: "tcp"

        val server = JsonObject().apply {
            addProperty("address", host)
            addProperty("port", port)
            addProperty("password", password)
        }
        val settings = JsonObject().apply { add("servers", JsonArray().apply { add(server) }) }
        val stream = buildStreamSettings(network, security, uri)

        return JsonObject().apply {
            addProperty("protocol", "trojan")
            addProperty("tag", "proxy")
            add("settings", settings)
            add("streamSettings", stream)
        }
    }

    private fun buildVmess(link: String): JsonObject {
        val b64 = link.removePrefix("vmess://")
        val json = JsonParser.parseString(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))).asJsonObject

        val user = JsonObject().apply {
            addProperty("id", json.get("id").asString)
            addProperty("alterId", json.get("aid")?.asInt ?: 0)
            addProperty("security", "auto")
        }
        val vnext = JsonObject().apply {
            addProperty("address", json.get("add").asString)
            addProperty("port", json.get("port").asString.toInt())
            add("users", JsonArray().apply { add(user) })
        }
        val settings = JsonObject().apply { add("vnext", JsonArray().apply { add(vnext) }) }

        val network = json.get("net")?.asString ?: "tcp"
        val security = if (json.get("tls")?.asString == "tls") "tls" else "none"
        val stream = JsonObject().apply {
            addProperty("network", network)
            addProperty("security", security)
            if (security == "tls") {
                add("tlsSettings", JsonObject().apply {
                    addProperty("serverName", json.get("sni")?.asString ?: json.get("host")?.asString ?: "")
                })
            }
        }

        return JsonObject().apply {
            addProperty("protocol", "vmess")
            addProperty("tag", "proxy")
            add("settings", settings)
            add("streamSettings", stream)
        }
    }

    private fun buildShadowsocks(link: String): JsonObject {
        val withoutScheme = link.removePrefix("ss://")
        val hashIdx = withoutScheme.indexOf('#')
        val body = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val atIdx = body.lastIndexOf('@')
        val userInfoB64 = body.substring(0, atIdx)
        val hostPort = body.substring(atIdx + 1)
        val decoded = String(android.util.Base64.decode(userInfoB64, android.util.Base64.URL_SAFE.or(android.util.Base64.NO_PADDING)))
        val (method, password) = decoded.split(":", limit = 2)
        val (host, portStr) = hostPort.split(":", limit = 2)

        val server = JsonObject().apply {
            addProperty("address", host)
            addProperty("port", portStr.toInt())
            addProperty("method", method)
            addProperty("password", password)
        }
        val settings = JsonObject().apply { add("servers", JsonArray().apply { add(server) }) }

        return JsonObject().apply {
            addProperty("protocol", "shadowsocks")
            addProperty("tag", "proxy")
            add("settings", settings)
            add("streamSettings", JsonObject().apply { addProperty("network", "tcp") })
        }
    }

    private fun buildStreamSettings(network: String, security: String, uri: Uri): JsonObject {
        val stream = JsonObject().apply {
            addProperty("network", network)
            addProperty("security", security)
        }
        when (security) {
            "reality" -> stream.add("realitySettings", JsonObject().apply {
                addProperty("serverName", uri.getQueryParameter("sni") ?: "")
                addProperty("fingerprint", uri.getQueryParameter("fp") ?: "chrome")
                addProperty("publicKey", uri.getQueryParameter("pbk") ?: "")
                addProperty("shortId", uri.getQueryParameter("sid") ?: "")
                addProperty("spiderX", URLDecoder.decode(uri.getQueryParameter("spx") ?: "/", "UTF-8"))
            })
            "tls" -> stream.add("tlsSettings", JsonObject().apply {
                addProperty("serverName", uri.getQueryParameter("sni") ?: "")
                addProperty("fingerprint", uri.getQueryParameter("fp") ?: "chrome")
                addProperty("allowInsecure", false)
            })
        }
        if (network == "ws") {
            stream.add("wsSettings", JsonObject().apply {
                addProperty("path", uri.getQueryParameter("path") ?: "/")
                add("headers", JsonObject().apply {
                    addProperty("Host", uri.getQueryParameter("host") ?: "")
                })
            })
        }
        return stream
    }
}
