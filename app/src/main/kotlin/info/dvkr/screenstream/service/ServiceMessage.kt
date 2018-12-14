package info.dvkr.screenstream.service

import android.os.Bundle
import android.os.Messenger
import android.os.Parcelable
import androidx.annotation.Keep
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.NetInterface
import info.dvkr.screenstream.data.model.TrafficPoint
import kotlinx.android.parcel.Parcelize

@Keep sealed class ServiceMessage : Parcelable {
    internal companion object {
        private const val MESSAGE_PARCELABLE = "MESSAGE_PARCELABLE"

        fun fromBundle(bundle: Bundle?): ServiceMessage? = bundle?.run {
            classLoader = ServiceMessage::class.java.classLoader
            getParcelable(MESSAGE_PARCELABLE)
        }
    }

    @Keep @Parcelize data class RegisterActivity(val relyTo: Messenger) : ServiceMessage()
    @Keep @Parcelize data class UnRegisterActivity(val relyTo: Messenger) : ServiceMessage()
    @Keep @Parcelize object FinishActivity : ServiceMessage()

    @Keep @Parcelize data class ServiceState(
        val isStreaming: Boolean,
        val isBusy: Boolean,
        val netInterfaces: List<NetInterface>,
        val appError: AppError?
    ) : ServiceMessage()

    @Keep @Parcelize data class Clients(val clients: List<HttpClient>) : ServiceMessage()
    @Keep @Parcelize data class TrafficHistory(val trafficHistory: List<TrafficPoint>) : ServiceMessage() {
        override fun toString(): String = this::class.java.simpleName
    }

    fun toBundle(): Bundle = Bundle().apply { putParcelable(MESSAGE_PARCELABLE, this@ServiceMessage) }

    override fun toString(): String = this::class.java.simpleName
}