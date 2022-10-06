package info.dvkr.screenstream.common

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

fun Any.getLog(tag: String? = "", msg: String? = "Invoked") =
    "${this.javaClass.simpleName}#${this.hashCode()}.$tag@${Thread.currentThread().name}: $msg"

fun InetAddress.asString(): String = if (this is Inet6Address) "[${this.hostAddress}]" else this.hostAddress ?: ""

fun InetSocketAddress?.asString(): String = "${this?.hostName?.let { it + "\n" }}${this?.address?.asString()}:${this?.port}"