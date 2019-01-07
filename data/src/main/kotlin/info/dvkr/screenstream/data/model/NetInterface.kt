package info.dvkr.screenstream.data.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.net.InetAddress

@Parcelize
data class NetInterface(
    val name: String,
    val address: InetAddress
) : Parcelable