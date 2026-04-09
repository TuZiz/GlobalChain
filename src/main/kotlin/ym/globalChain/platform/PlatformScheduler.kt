package ym.globalchain.platform

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class PlatformScheduler(private val plugin: Plugin) {

    private val server = plugin.server
    private val folia = runCatching { server.javaClass.getMethod("getGlobalRegionScheduler") }.isSuccess
    private val globalScheduler: Any? = if (folia) server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server) else null
    private val globalRunMethod: Method? = globalScheduler?.javaClass?.methods?.firstOrNull { it.name == "run" && it.parameterCount == 2 }
    private val globalRunDelayedMethod: Method? = globalScheduler?.javaClass?.methods?.firstOrNull { it.name == "runDelayed" && it.parameterCount == 3 }
    private val bukkitScheduler = server.scheduler

    fun isFolia(): Boolean = folia

    fun runGlobal(task: () -> Unit): CompletableFuture<Unit> = supplyGlobal {
        task()
    }

    fun runGlobalLater(delayTicks: Long, task: () -> Unit): CompletableFuture<Unit> = supplyGlobalLater(delayTicks) {
        task()
    }

    fun <T> supplyGlobal(task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (folia && globalScheduler != null && globalRunMethod != null) {
            try {
                globalRunMethod.invoke(globalScheduler, plugin, Consumer<Any?> {
                    completeFuture(future, task)
                })
                return future
            } catch (_: Throwable) {
            }
        }
        bukkitScheduler.runTask(plugin, Runnable {
            completeFuture(future, task)
        })
        return future
    }

    fun <T> supplyGlobalLater(delayTicks: Long, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (folia && globalScheduler != null && globalRunDelayedMethod != null) {
            try {
                globalRunDelayedMethod.invoke(globalScheduler, plugin, Consumer<Any?> {
                    completeFuture(future, task)
                }, delayTicks)
                return future
            } catch (_: Throwable) {
            }
        }
        bukkitScheduler.runTaskLater(plugin, Runnable {
            completeFuture(future, task)
        }, delayTicks)
        return future
    }

    fun runOnPlayer(player: Player, task: () -> Unit): CompletableFuture<Unit> = supplyOnPlayer(player) {
        task()
    }

    fun runOnPlayerLater(player: Player, delayTicks: Long, task: () -> Unit): CompletableFuture<Unit> = supplyOnPlayerLater(player, delayTicks) {
        task()
    }

    fun <T> supplyOnPlayer(player: Player, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (folia) {
            try {
                val entityScheduler = player.javaClass.getMethod("getScheduler").invoke(player)
                val runMethod = entityScheduler.javaClass.methods.firstOrNull { it.name == "run" && it.parameterCount == 3 }
                if (runMethod != null) {
                    runMethod.invoke(
                        entityScheduler,
                        plugin,
                        Consumer<Any?> {
                            completeFuture(future, task)
                        },
                        Runnable {
                            future.completeExceptionally(IllegalStateException("Player scheduler retired for ${player.uniqueId}."))
                        },
                    )
                    return future
                }
            } catch (_: Throwable) {
            }
        }
        bukkitScheduler.runTask(plugin, Runnable {
            completeFuture(future, task)
        })
        return future
    }

    fun <T> supplyOnPlayerLater(player: Player, delayTicks: Long, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (folia) {
            try {
                val entityScheduler = player.javaClass.getMethod("getScheduler").invoke(player)
                val runMethod = entityScheduler.javaClass.methods.firstOrNull { it.name == "runDelayed" && it.parameterCount == 4 }
                if (runMethod != null) {
                    runMethod.invoke(
                        entityScheduler,
                        plugin,
                        Consumer<Any?> {
                            completeFuture(future, task)
                        },
                        Runnable {
                            future.completeExceptionally(IllegalStateException("Player scheduler retired for ${player.uniqueId}."))
                        },
                        delayTicks,
                    )
                    return future
                }
            } catch (_: Throwable) {
            }
        }
        bukkitScheduler.runTaskLater(plugin, Runnable {
            completeFuture(future, task)
        }, delayTicks)
        return future
    }

    fun dispatch(sender: CommandSender, task: () -> Unit): CompletableFuture<Unit> {
        return if (sender is Player) runOnPlayer(sender, task) else runGlobal(task)
    }

    private fun <T> completeFuture(future: CompletableFuture<T>, task: () -> T) {
        try {
            future.complete(task())
        } catch (throwable: Throwable) {
            future.completeExceptionally(throwable)
        }
    }
}
