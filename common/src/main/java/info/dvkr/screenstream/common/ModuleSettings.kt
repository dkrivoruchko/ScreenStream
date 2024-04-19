package info.dvkr.screenstream.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope

public interface ModuleSettings {
    public val id: String
    public val groups: List<Group>

    @Composable
    public fun TitleUI(horizontalPadding: Dp, modifier: Modifier)

    public fun filterBy(resources: Resources, test: String): ModuleSettings? {
        val filteredGroups = groups.mapNotNull { group -> group.filterBy(resources, test) }.ifEmpty { return null }

        return object : ModuleSettings {
            override val id: String = this@ModuleSettings.id

            override val groups: List<Group> = filteredGroups

            @Composable
            override fun TitleUI(horizontalPadding: Dp, modifier: Modifier) = this@ModuleSettings.TitleUI(horizontalPadding, modifier)

            override fun filterBy(resources: Resources, test: String): ModuleSettings? = null
        }
    }

    public interface Group {
        public val id: String
        public val position: Int
        public val items: List<Item>

        @Composable
        public fun TitleUI(horizontalPadding: Dp, modifier: Modifier): Unit = Unit

        public fun filterBy(resources: Resources, text: String): Group? {
            val filteredItems = items.filter { item -> item.has(resources, text) }.ifEmpty { return null }

            return object : Group {
                override val id: String = this@Group.id
                override val position: Int = this@Group.position
                override val items: List<Item> = filteredItems

                @Composable
                override fun TitleUI(horizontalPadding: Dp, modifier: Modifier) = this@Group.TitleUI(horizontalPadding, modifier)

                override fun filterBy(resources: Resources, text: String): Group? = null
            }
        }
    }

    public interface Item {
        public val id: String
        public val position: Int
        public val available: Boolean

        public fun has(resources: Resources, text: String): Boolean

        @Composable
        public fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit)

        @Composable
        public fun DetailUI(onBackClick: () -> Unit, headerContent: @Composable (String) -> Unit): Unit = Unit
    }
}