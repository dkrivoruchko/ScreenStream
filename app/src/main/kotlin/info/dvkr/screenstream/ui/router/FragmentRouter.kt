package info.dvkr.screenstream.ui.router

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog

class FragmentRouter(private val fragmentManager: FragmentManager, vararg fragmentCreators: FragmentCreator) {

    interface FragmentCreator {
        @IdRes fun getMenuItemId(): Int
        fun getTag(): String
        fun newInstance(): Fragment
    }

    private val knownFragmentCreators = fragmentCreators.toList()

    init {
        XLog.i(getLog("init", "knownFragmentCreators: ${knownFragmentCreators.joinToString { it.getTag() }}"))
    }

    fun navigateTo(@IdRes menuItemId: Int): Boolean {
        XLog.i(getLog("navigateTo", "menuItemId: $menuItemId"))

        val currentFragment = fragmentManager.fragments.firstOrNull { it.isVisible }
        val navigateToFragment = knownFragmentCreators.firstOrNull { it.getMenuItemId() == menuItemId }

        XLog.i(getLog("navigateTo", "navigateToFragment: ${navigateToFragment?.getTag()}"))

        require(navigateToFragment != null)
        val newFragment = fragmentManager.findFragmentByTag(navigateToFragment.getTag())

        if (currentFragment != null && currentFragment == newFragment) return true

        if (newFragment == null) {
            fragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.nav_default_enter_anim, R.anim.nav_default_exit_anim,
                    R.anim.nav_default_enter_anim, R.anim.nav_default_exit_anim
                )
                .replace(R.id.fl_activity_single, navigateToFragment.newInstance(), navigateToFragment.getTag())
                .addToBackStack(navigateToFragment.getTag())
                .commitAllowingStateLoss()
        } else {
            XLog.i(getLog("navigateTo", "newFragment !=null: ${navigateToFragment.getTag()}"))
            fragmentManager.popBackStackImmediate(navigateToFragment.getTag(), 0)
        }
        return true
    }

    fun onBackPressed(): Boolean = fragmentManager.popBackStackImmediate()

    @IdRes
    fun getCurrentMenuItemId(): Int {
        val currentFragmentTag = fragmentManager.fragments.firstOrNull { it.isVisible }?.tag
        val currentFragmentCreator = knownFragmentCreators.firstOrNull { it.getTag() == currentFragmentTag }
        return currentFragmentCreator?.getMenuItemId() ?: 0
    }
}