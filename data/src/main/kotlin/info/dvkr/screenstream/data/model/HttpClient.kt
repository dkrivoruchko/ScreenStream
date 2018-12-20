package info.dvkr.screenstream.data.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.net.InetSocketAddress

@Parcelize
data class HttpClient(
    val clientAddress: InetSocketAddress,
    val isSlowConnection: Boolean,
    val isDisconnected: Boolean
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HttpClient
        if (clientAddress != other.clientAddress) return false
        return true
    }

    override fun hashCode(): Int {
        return clientAddress.hashCode()
    }
}