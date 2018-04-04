package info.dvkr.screenstream.ui

import android.app.Dialog
import android.app.DialogFragment
import android.content.Context
import android.os.Bundle
import android.support.annotation.Keep
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import timber.log.Timber


class NotificationDialog : BaseDialog() {
    companion object {
        fun newInstance(
            dialogTag: String,
            titleText: String = "",
            messageText: String,
            positiveButtonText: String,
            negativeButtonText: String = "",
            isCancelable: Boolean = true,
            data: String = ""
        ) =
            BaseDialog.newInstance(
                dialogTag = dialogTag,
                titleText = titleText,
                messageText = messageText,
                positiveButtonText = positiveButtonText,
                negativeButtonText = negativeButtonText,
                isCancelable = isCancelable,
                data = data
            )
    }
}


open class BaseDialog : DialogFragment() {

    interface DialogCallback {
        @Keep open class Result(val dialogTag: String) {
            @Keep class Positive(tag: String, val data: String = "") : Result(tag)
            @Keep class Negative(tag: String) : Result(tag)
            @Keep class Neutral(tag: String) : Result(tag)
        }

        fun onDialogResult(result: Result): Any
    }

    companion object {
        internal const val DIALOG_TAG = "DIALOG_TAG"
        internal const val TITLE_TEXT = "TITLE_TEXT"
        internal const val MESSAGE_TEXT = "MESSAGE_TEXT"
        internal const val POSITIVE_TEXT = "POSITIVE_TEXT"
        internal const val NEGATIVE_TEXT = "NEGATIVE_TEXT"
        internal const val NEUTRAL_TEXT = "NEUTRAL_TEXT"
        internal const val IS_CANCELABLE = "IS_CANCELABLE"
        internal const val DIALOG_DATA = "DIALOG_DATA"

        fun newInstance(
            context: Context,
            dialogTag: String,
            @StringRes titleResId: Int = 0,
            @StringRes messageResId: Int = 0,
            @StringRes positiveButtonResId: Int = android.R.string.ok,
            @StringRes negativeButtonResId: Int = android.R.string.cancel,
            @StringRes neutralButtonResId: Int = 0,
            isCancelable: Boolean = true
        ) =
            newInstance(
                dialogTag = dialogTag,
                titleText = if (titleResId != 0) context.getString(titleResId) else "",
                messageText = if (messageResId != 0) context.getString(messageResId) else "",
                positiveButtonText = if (positiveButtonResId != 0) context.getString(positiveButtonResId) else "",
                negativeButtonText = if (negativeButtonResId != 0) context.getString(negativeButtonResId) else "",
                neutralButtonText = if (neutralButtonResId != 0) context.getString(neutralButtonResId) else "",
                isCancelable = isCancelable
            )

        fun newInstance(
            dialogTag: String,
            titleText: String = "",
            messageText: String = "",
            positiveButtonText: String = "",
            negativeButtonText: String = "",
            neutralButtonText: String = "",
            isCancelable: Boolean = true,
            data: String = ""
        ) =
            BaseDialog().apply {
                arguments = Bundle().apply {
                    putString(DIALOG_TAG, dialogTag)
                    putString(TITLE_TEXT, titleText)
                    putString(MESSAGE_TEXT, messageText)
                    putString(POSITIVE_TEXT, positiveButtonText)
                    putString(NEGATIVE_TEXT, negativeButtonText)
                    putString(NEUTRAL_TEXT, neutralButtonText)
                    putBoolean(IS_CANCELABLE, isCancelable)
                    putString(DIALOG_DATA, data)
                }
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(activity ?: throw IllegalStateException("Activity is null")).apply {
            arguments?.apply {
                isCancelable = getBoolean(IS_CANCELABLE)

                val dialogTag = getString(DIALOG_TAG) ?: throw IllegalStateException("Tag not set")
                getString(TITLE_TEXT)?.let { setTitle(it) }
                getString(MESSAGE_TEXT)?.let { setMessage(it) }

                val data = getString(DIALOG_DATA) ?: ""

                getString(POSITIVE_TEXT)?.let {
                    setPositiveButton(it, { _, _ -> sendDialogResult(DialogCallback.Result.Positive(dialogTag, data)) })
                }

                getString(NEGATIVE_TEXT)?.let {
                    setNegativeButton(it, { _, _ -> sendDialogResult(DialogCallback.Result.Negative(dialogTag)) })
                }

                getString(NEUTRAL_TEXT)?.let {
                    setNeutralButton(it, { _, _ -> sendDialogResult(DialogCallback.Result.Neutral(dialogTag)) })
                }
            }
        }.create().apply { setCanceledOnTouchOutside(false) }
    }

    protected fun sendDialogResult(result: DialogCallback.Result) =
        if (targetFragment != null && targetFragment is DialogCallback) {
            (targetFragment as DialogCallback).onDialogResult(result)
        } else if (activity != null && activity is DialogCallback) {
            (activity as DialogCallback).onDialogResult(result)
        } else {
            Timber.w("BaseDialog: No target implements DialogCallback")
        }
}