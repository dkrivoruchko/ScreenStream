package info.dvkr.screenstream.domain.utils

object Utils {

    @JvmStatic
    fun getLogPrefix(obj: Any): String =
            "${obj.javaClass.simpleName}@${Thread.currentThread().name}#${obj.hashCode()}"
}

