package info.dvkr.screenstream

import android.app.Activity
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import androidx.core.content.edit
import com.elvishew.xlog.XLog
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import info.dvkr.screenstream.common.getLog
import kotlin.coroutines.cancellation.CancellationException

public object AppReview {
    private const val APP_REVIEW_REQUEST_TIMEOUT = 30 * 24 * 60 * 60 * 1000L  // 30 days. Don't need exact time frame
    private const val LAST_REVIEW_REQUEST_TIME = "LAST_REVIEW_REQUEST_TIME"

    private lateinit var sharedPreferences: SharedPreferences
    private var lastReviewRequest: Long = 0

    public suspend fun showReviewUi(activity: Activity) {
        if (::sharedPreferences.isInitialized.not()) {
            sharedPreferences = activity.getSharedPreferences("play_review.xml", MODE_PRIVATE)
        }

        try {
            lastReviewRequest = sharedPreferences.getLong(LAST_REVIEW_REQUEST_TIME, 0)
            if (lastReviewRequest <= 0) {
                sharedPreferences.edit { putLong(LAST_REVIEW_REQUEST_TIME, System.currentTimeMillis() - 20 * 24 * 60 * 60 * 1000L) }
                return
            }
            if (System.currentTimeMillis() - lastReviewRequest < APP_REVIEW_REQUEST_TIMEOUT) return

            val reviewManager = ReviewManagerFactory.create(activity) // FakeReviewManager
            reviewManager.launchReview(activity, reviewManager.requestReview())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            XLog.d(activity.getLog("AppReview.showReviewUI", "Error: ${error}"))
        } finally {
            sharedPreferences.edit { putLong(LAST_REVIEW_REQUEST_TIME, System.currentTimeMillis()) }
        }
    }
}