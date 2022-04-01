package com.programmersbox.cachefun

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.icu.text.SimpleDateFormat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.programmersbox.cachefun.ui.theme.CacheFunTheme
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

class MainActivity : ComponentActivity() {

    private var count = 0

    private val map = mutableStateMapOf<Int, String>()

    /*private val cache = ExpirableLRUCache(
        delegate = object : GenericCache<Int, String> {
            override val size: Int get() = map.size

            override fun set(key: Int, value: String) {
                map[key] = value
            }

            override fun get(key: Int): String? = map[key]
            override fun remove(key: Int): String? = map.remove(key)
            override fun clear() = map.clear()
        },
        minimalSize = 5,
        flushInterval = TimeUnit.MINUTES.toMillis(5)
    )*/

    private val cache = ExpirableLRUCache<Int, String>(
        minimalSize = 5,
        flushInterval = TimeUnit.MINUTES.toMillis(5)
    ) {
        size = map.size
        set { key, value -> map[key] = value }
        get { map[it] }
        remove { map.remove(it) }
        clear { map.clear() }
    }

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CacheFunTheme {
                // A surface container using the 'background' color from the theme
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Cache Fun") }) },
                    bottomBar = {
                        BottomAppBar {
                            OutlinedButton(
                                onClick = { cache[count++] = SimpleDateFormat.getDateTimeInstance().format(System.currentTimeMillis()) }
                            ) { Text("Add to the cache") }
                        }
                    }
                ) { p ->

                    LazyColumn(contentPadding = p) {

                        item {
                            val time by currentTime()
                            Text("The current time is: ${SimpleDateFormat.getDateTimeInstance().format(time)}")
                        }

                        items(map.toList()) { Text("${it.first} == ${it.second}") }

                    }

                }
            }
        }
    }
}

@DslMarker
annotation class GenericCacheMarker

/**
 * A Generic K,V [GenericCache] defines the basic operations to a cache.
 */
interface GenericCache<K, V> {
    /**
     * The number of the items that are currently cached.
     */
    val size: Int

    /**
     * Cache a [value] with a given [key]
     */
    operator fun set(key: K, value: V)

    /**
     * Get the cached value of a given [key], or null if it's not cached or evicted.
     */
    operator fun get(key: K): V?

    /**
     * Remove the value of the [key] from the cache, and return the removed value, or null if it's not cached at all.
     */
    fun remove(key: K): V?

    /**
     * Remove all the items in the cache.
     */
    fun clear()

    class GenericCacheBuilder<K, V> {
        @GenericCacheMarker
        var size: Int by Delegates.notNull()
        private var set: (key: K, value: V) -> Unit by Delegates.notNull()
        private var get: (key: K) -> V? by Delegates.notNull()
        private var remove: (key: K) -> V? by Delegates.notNull()
        private var clear: () -> Unit by Delegates.notNull()

        @GenericCacheMarker
        fun set(block: (key: K, value: V) -> Unit) {
            set = block
        }

        @GenericCacheMarker
        fun get(block: (key: K) -> V?) {
            get = block
        }

        @GenericCacheMarker
        fun remove(block: (key: K) -> V?) {
            remove = block
        }

        @GenericCacheMarker
        fun clear(block: () -> Unit) {
            clear = block
        }

        internal fun build() = object : GenericCache<K, V> {
            override val size: Int get() = this@GenericCacheBuilder.size
            override fun set(key: K, value: V) = this@GenericCacheBuilder.set(key, value)
            override fun get(key: K): V? = this@GenericCacheBuilder.get(key)
            override fun remove(key: K): V? = this@GenericCacheBuilder.remove(key)
            override fun clear() = this@GenericCacheBuilder.clear()
        }
    }
}


/**
 * [ExpirableLRUCache] flushes items that are **Least Recently Used** and keeps [minimalSize] items at most
 * along with flushing the items whose life time is longer than [flushInterval].
 */
class ExpirableLRUCache<K, V>(
    private val minimalSize: Int = DEFAULT_SIZE,
    private val flushInterval: Long = TimeUnit.MINUTES.toMillis(1),
    private val delegate: GenericCache<K, V>,
) : GenericCache<K, V> by delegate {

    constructor(minimalSize: Int, flushInterval: Long, delegate: GenericCache.GenericCacheBuilder<K, V>.() -> Unit) : this(
        minimalSize,
        flushInterval,
        GenericCache.GenericCacheBuilder<K, V>().apply(delegate).build()
    )

    private val keyMap = object : LinkedHashMap<K, Boolean>(minimalSize, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Boolean>): Boolean {
            val tooManyCachedItems = size > minimalSize
            if (tooManyCachedItems) eldestKeyToRemove = eldest.key
            return tooManyCachedItems
        }
    }

    private var lastFlushTime = System.nanoTime()

    private var eldestKeyToRemove: K? = null

    override val size: Int
        get() {
            recycle()
            return delegate.size
        }

    override fun set(key: K, value: V) {
        recycle()
        delegate[key] = value
        cycleKeyMap(key)
    }

    override fun remove(key: K): V? {
        recycle()
        return delegate.remove(key)
    }

    override fun get(key: K): V? {
        recycle()
        keyMap[key]
        return delegate[key]
    }

    override fun clear() {
        keyMap.clear()
        delegate.clear()
    }

    private fun cycleKeyMap(key: K) {
        keyMap[key] = PRESENT
        eldestKeyToRemove?.let { delegate.remove(it) }
        eldestKeyToRemove = null
    }

    private fun recycle() {
        val shouldRecycle = System.nanoTime() - lastFlushTime >= TimeUnit.MILLISECONDS.toNanos(flushInterval)
        if (shouldRecycle) {
            delegate.clear()
            lastFlushTime = System.nanoTime()
        }
    }

    companion object {
        private const val DEFAULT_SIZE = 100
        private const val PRESENT = true
    }
}

/**
 * Creates a broadcast receiver that gets the time every tick that Android takes and
 * unregisters the receiver when the view is at the end of its lifecycle
 */
@Composable
fun currentTime(): State<Long> {
    return broadcastReceiver(
        defaultValue = System.currentTimeMillis(),
        intentFilter = IntentFilter(Intent.ACTION_TIME_TICK),
        tick = { _, _ -> System.currentTimeMillis() }
    )
}

/**
 * Registers a broadcast receiver and unregisters at the end of the composable lifecycle
 *
 * @param defaultValue the default value that this starts as
 * @param intentFilter the filter for intents
 * @see IntentFilter
 * @param tick the callback from the broadcast receiver
 */
@Composable
fun <T : Any> broadcastReceiver(defaultValue: T, intentFilter: IntentFilter, tick: (context: Context, intent: Intent) -> T): State<T> {
    val item: MutableState<T> = remember { mutableStateOf(defaultValue) }
    val context = LocalContext.current

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                item.value = tick(context, intent)
            }
        }
        context.registerReceiver(receiver, intentFilter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return item
}