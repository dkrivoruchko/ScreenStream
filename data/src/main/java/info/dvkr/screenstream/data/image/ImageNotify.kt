package info.dvkr.screenstream.data.image


interface ImageNotify {
    companion object {
        const val IMAGE_TYPE_NONE = "IMAGE_TYPE_NONE"
        const val IMAGE_TYPE_DEFAULT = "IMAGE_TYPE_DEFAULT"
        const val IMAGE_TYPE_NEW_ADDRESS = "IMAGE_TYPE_NEW_ADDRESS"
        const val IMAGE_TYPE_RELOAD_PAGE = "IMAGE_TYPE_RELOAD_PAGE"
    }

    fun getImage(imageType: String): ByteArray
}