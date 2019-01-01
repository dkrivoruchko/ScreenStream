package info.dvkr.screenstream.ui.router

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import java.util.*

class FragmentRouter(private val fragmentManager: FragmentManager, vararg fragmentCreators: FragmentCreator) {

    interface FragmentCreator {
        @IdRes
        fun getMenuItemId(): Int

        fun getTag(): String
        fun newInstance(): Fragment
    }

    private val knownFragmentCreators = fragmentCreators.toList()
    private val localStack = LinkedList<String>()

    init {
        repeat(fragmentManager.backStackEntryCount) {
            localStack.addLast(fragmentManager.getBackStackEntryAt(it).name)
        }

        XLog.i(getLog("init", "knownFragmentCreators: ${knownFragmentCreators.joinToString { it.getTag() }}"))
        XLog.i(getLog("init", "localStack: ${localStack.joinToString()}"))
    }

    fun navigateTo(@IdRes menuItemId: Int): Boolean {
        val currentFragment = fragmentManager.fragments.firstOrNull { it.isVisible }
        val fragmentCreator = knownFragmentCreators.firstOrNull { it.getMenuItemId() == menuItemId }

        XLog.i(getLog("navigateTo", "localStack: ${localStack.joinToString()}"))
        XLog.i(getLog("navigateTo", "menuItemId: $menuItemId"))
        XLog.i(getLog("navigateTo", "fragmentCreator: ${fragmentCreator?.getTag()}"))

        require(fragmentCreator != null)
        val newFragment = fragmentManager.findFragmentByTag(fragmentCreator.getTag())

        if (currentFragment != null && currentFragment == newFragment) return true

        if (newFragment == null) {
            fragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.nav_default_enter_anim, R.anim.nav_default_exit_anim,
                    R.anim.nav_default_enter_anim, R.anim.nav_default_exit_anim
                )
                .replace(R.id.fl_activity_single, fragmentCreator.newInstance(), fragmentCreator.getTag())
                .addToBackStack(fragmentCreator.getTag())
                .commit()

            localStack.addLast(fragmentCreator.getTag())
        } else {
            val index = localStack.indexOf(fragmentCreator.getTag())
            XLog.i(getLog("navigateTo", "fragmentCreator: ${fragmentCreator.getTag()}"))

            require(index >= 0)
            repeat(localStack.size - index - 1) { localStack.removeLast() }
            fragmentManager.popBackStack(fragmentCreator.getTag(), 0)
        }
        return true
    }

    @IdRes
    fun onBackPressed(): Int {
        localStack.isEmpty().not() || return 0
        fragmentManager.popBackStack()
        localStack.removeLast()
        localStack.isEmpty().not() || return 0
        return knownFragmentCreators.firstOrNull { it.getTag() == localStack.last }?.getMenuItemId() ?: 0
    }
}