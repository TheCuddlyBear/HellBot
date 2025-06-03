package HellBot.extensions

import HellBot.i18n.Translations
import dev.arbjerg.lavalink.protocol.v4.LoadResult
import dev.arbjerg.lavalink.protocol.v4.Track
import dev.kord.common.entity.ButtonStyle
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import dev.kord.rest.builder.message.embed
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.converters.impl.attachment
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.components.components
import dev.kordex.core.components.publicButton
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.i18n.withContext
import dev.kordex.core.utils.delete
import dev.schlaubi.lavakord.audio.TrackEndEvent
import dev.schlaubi.lavakord.audio.TrackStartEvent
import dev.schlaubi.lavakord.audio.on
import dev.schlaubi.lavakord.kord.lavakord
import dev.schlaubi.lavakord.plugins.lavasrc.LavaSrc
import dev.schlaubi.lavakord.plugins.sponsorblock.Sponsorblock
import dev.schlaubi.lavakord.rest.loadItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class MusicExtension : Extension() {
	override val name = "music"

	private val lavalink = this.bot.kordRef.lavakord {
		plugins {
			install(LavaSrc)
			install(Sponsorblock)
		}
	}

	// Thread-safe collections for managing guild data
	private val guildQueues = ConcurrentHashMap<ULong, MusicQueue>()
	private val guildMutexes = ConcurrentHashMap<ULong, Mutex>()
	private val setupEventListeners = ConcurrentHashMap<ULong, Boolean>()

	/**
	 * Data class representing a music queue for a guild
	 */
	private data class MusicQueue(
		val tracks: ArrayDeque<Track> = ArrayDeque(),
		var controlMessage: Message? = null,
		var isPlaying: Boolean = false,
		var currentTrack: Track? = null,
		var textChannelId: ULong? = null
	) {
		fun addTrack(track: Track) = tracks.addLast(track)
		fun nextTrack(): Track? = tracks.removeFirstOrNull()
		fun clear() = tracks.clear()
		fun isEmpty(): Boolean = tracks.isEmpty()
		fun size(): Int = tracks.size
	}

	/**
	 * Get or create a mutex for the given guild
	 */
	private fun getMutex(guildId: ULong): Mutex =
		guildMutexes.computeIfAbsent(guildId) { Mutex() }

	/**
	 * Get or create a music queue for the given guild
	 */
	private fun getOrCreateQueue(guildId: ULong): MusicQueue =
		guildQueues.computeIfAbsent(guildId) { MusicQueue() }

	/**
	 * Setup event listeners for a guild (only once per guild)
	 */
	private fun setupEventListeners(guildId: ULong) {
		// Only setup listeners if not already done for this guild
		if (setupEventListeners.putIfAbsent(guildId, true) == null) {
			val player = lavalink.getLink(guildId).player

			player.on<TrackStartEvent> {
				bot.logger.info { "Track started: ${track.info.title} in guild $guildId" }
				handleTrackStart(guildId, track)
			}

			player.on<TrackEndEvent> {
				bot.logger.info { "Track ended in guild $guildId" }
				handleTrackEnd(guildId)
			}

			bot.logger.info { "Event listeners setup for guild $guildId" }
		}
	}

	/**
	 * Handle track start event
	 */
	private suspend fun handleTrackStart(guildId: ULong, track: Track) {
		val mutex = getMutex(guildId)
		mutex.withLock {
			val queue = getOrCreateQueue(guildId)
			queue.isPlaying = true
			queue.currentTrack = track

			bot.logger.info { "Updating control message for track: ${track.info.title}" }
			updateOrCreateControlMessage(guildId, track, queue)
		}
	}

	/**
	 * Handle track end event
	 */
	private suspend fun handleTrackEnd(guildId: ULong) {
		val mutex = getMutex(guildId)
		mutex.withLock {
			val queue = getOrCreateQueue(guildId)
			val player = lavalink.getLink(guildId).player

			val nextTrack = queue.nextTrack()
			if (nextTrack != null) {
				bot.logger.info { "Playing next track: ${nextTrack.info.title}" }
				player.playTrack(nextTrack)
			} else {
				bot.logger.info { "Queue empty, cleaning up guild $guildId" }
				cleanupInternal(guildId)  // Use internal cleanup to avoid deadlock
			}
		}
	}

	/**
	 * Create or update the control message
	 */
	private suspend fun updateOrCreateControlMessage(guildId: ULong, track: Track, queue: MusicQueue) {
		try {
			val textChannelId = queue.textChannelId
			if (textChannelId == null) {
				bot.logger.warn { "No text channel ID stored for guild $guildId" }
				return
			}

			val textChannel = bot.kordRef.getChannelOf<dev.kord.core.entity.channel.TextChannel>(dev.kord.common.entity.Snowflake(textChannelId))
			if (textChannel == null) {
				bot.logger.warn { "Could not find text channel $textChannelId for guild $guildId" }
				return
			}

			// Try to update existing message first
			val controlMessage = queue.controlMessage
			var messageUpdated = false

			if (controlMessage != null) {
				try {
					controlMessage.edit {
						embed {
							author { name = "🎵 Music Controller" }
							thumbnail {
								url = track.info.artworkUrl ?: "https://cdn.discordapp.com/embed/avatars/0.png"
							}
							field {
								name = "Now playing"
								value = track.info.title ?: "Unknown Title"
								inline = false
							}
							field {
								name = "Author"
								value = track.info.author ?: "Unknown Artist"
								inline = true
							}
							field {
								name = "Queue"
								value = "${queue.size()} tracks remaining"
								inline = true
							}
							footer {
								text = "Node: ${lavalink.getLink(guildId).node.name}"
							}
						}

						components {
							publicButton {
								label = Translations.Music.Buttons.Pause.name
								style = ButtonStyle.Primary
								action {
									togglePause(guildId)
								}
							}

							publicButton {
								label = Translations.Music.Buttons.Next.name
								style = ButtonStyle.Primary
								action {
									skipTrack(guildId)
								}
							}

							publicButton {
								label = Translations.Music.Buttons.Stop.name
								style = ButtonStyle.Danger
								action {
									stopMusic(guildId)
								}
							}
						}
					}
					messageUpdated = true
					bot.logger.info { "Updated existing control message for guild $guildId" }
				} catch (e: Exception) {
					bot.logger.warn(e) { "Failed to update existing control message, creating new one" }
					// Message probably doesn't exist anymore, create a new one
				}
			}

			// Create new message if update failed or no existing message
			if (!messageUpdated) {
				val newMessage = textChannel.createMessage {
					embed {
						author { name = "🎵 Music Controller" }
						thumbnail {
							url = track.info.artworkUrl ?: "https://cdn.discordapp.com/embed/avatars/0.png"
						}
						field {
							name = "Now playing"
							value = track.info.title ?: "Unknown Title"
							inline = false
						}
						field {
							name = "Author"
							value = track.info.author ?: "Unknown Artist"
							inline = true
						}
						field {
							name = "Queue"
							value = "${queue.size()} tracks remaining"
							inline = true
						}
						footer {
							text = "Node: ${lavalink.getLink(guildId).node.name}"
						}
					}

					components {
						publicButton {
							label = Translations.Music.Buttons.Pause.name
							style = ButtonStyle.Primary
							action {
								togglePause(guildId)
							}
						}

						publicButton {
							label = Translations.Music.Buttons.Next.name
							style = ButtonStyle.Primary
							action {
								skipTrack(guildId)
							}
						}

						publicButton {
							label = Translations.Music.Buttons.Stop.name
							style = ButtonStyle.Danger
							action {
								stopMusic(guildId)
							}
						}
					}
				}
				queue.controlMessage = newMessage
				bot.logger.info { "Created new control message for guild $guildId" }
			}
		} catch (e: Exception) {
			bot.logger.error(e) { "Failed to create/update control message for guild $guildId" }
		}
	}

	/**
	 * Play or enqueue a track
	 */
	private suspend fun playOrEnqueueTrack(guildId: ULong, track: Track, textChannelId: ULong) {
		val mutex = getMutex(guildId)
		mutex.withLock {
			val queue = getOrCreateQueue(guildId)
			val link = lavalink.getLink(guildId)
			val player = link.player

			// Store the text channel ID for control messages
			queue.textChannelId = textChannelId

			bot.logger.info { "Processing track: ${track.info.title}, queue playing: ${queue.isPlaying}, queue size: ${queue.size()}" }

			if (!queue.isPlaying && queue.isEmpty()) {
				// This is the first track - start playing immediately
				bot.logger.info { "Starting playback for first track: ${track.info.title}" }

				// Setup event listeners before playing
				setupEventListeners(guildId)

				// Start playing
				player.playTrack(track)
			} else {
				// Add to queue
				queue.addTrack(track)
				bot.logger.info { "Added track to queue: ${track.info.title}, new queue size: ${queue.size()}" }

				// Send a simple notification to the text channel about the queued track
				try {
					val textChannel = bot.kordRef.getChannelOf<dev.kord.core.entity.channel.TextChannel>(dev.kord.common.entity.Snowflake(textChannelId))
					textChannel?.createMessage {
						content = "🎵 Added to queue: **${track.info.title}** by **${track.info.author}** (Position: ${queue.size()})"
					}?.delete(millis=10000)
				} catch (e: Exception) {
					bot.logger.error(e) { "Failed to send queue notification" }
				}
			}
		}
	}

	/**
	 * Toggle pause/resume
	 */
	private suspend fun togglePause(guildId: ULong) {
		val player = lavalink.getLink(guildId).player
		if (player.paused) {
			player.unPause()
		} else {
			player.pause()
		}
	}

	/**
	 * Skip current track
	 */
	private suspend fun skipTrack(guildId: ULong) {
		val player = lavalink.getLink(guildId).player
		player.stopTrack()
	}

	/**
	 * Stop music and cleanup
	 */
	private suspend fun stopMusic(guildId: ULong) {
		cleanup(guildId)
	}

	/**
	 * Public cleanup function that acquires mutex (for external calls like stop button)
	 */
	private suspend fun cleanup(guildId: ULong) {
		val mutex = getMutex(guildId)
		mutex.withLock {
			cleanupInternal(guildId)
		}
	}

	/**
	 * Internal cleanup function that doesn't acquire mutex (for calls from within mutex-locked contexts)
	 */
	private suspend fun cleanupInternal(guildId: ULong) {
		try {
			val queue = guildQueues[guildId]

			// Try to delete control message, but don't fail if it's already gone
			try {
				queue?.controlMessage?.delete()
			} catch (e: Exception) {
				bot.logger.debug(e) { "Control message already deleted or inaccessible for guild $guildId" }
			}

			lavalink.getLink(guildId).destroy()
			guildQueues.remove(guildId)
			guildMutexes.remove(guildId)
			setupEventListeners.remove(guildId)

			bot.logger.info { "Cleaned up resources for guild $guildId" }
		} catch (e: Exception) {
			bot.logger.error(e) { "Error during cleanup for guild $guildId" }
		}
	}

	/**
	 * Load and handle different track result types
	 */
	private suspend fun loadAndHandleTrack(
		guildId: ULong,
		query: String,
		textChannelId: ULong,
		respond: suspend (String) -> Unit
	) {
		val link = lavalink.getLink(guildId)

		try {
			when (val result = link.loadItem(query)) {
				is LoadResult.TrackLoaded -> {
					val track = result.data
					bot.logger.info { "Loaded single track: ${track.info.title}" }
					respond("🎵 Loading: **${track.info.title}** by **${track.info.author}**")
					playOrEnqueueTrack(guildId, track, textChannelId)
				}

				is LoadResult.PlaylistLoaded -> {
					val tracks = result.data.tracks
					if (tracks.isNotEmpty()) {
						val firstTrack = tracks.first()
						bot.logger.info { "Loaded playlist with ${tracks.size} tracks" }
						respond(
							"🎵 Loading playlist: **${result.data.info.name}** (${tracks.size} tracks)\n" +
								"Starting with: **${firstTrack.info.title}**"
						)

						// Add first track
						playOrEnqueueTrack(guildId, firstTrack, textChannelId)

						// Add remaining tracks to queue
						val mutex = getMutex(guildId)
						mutex.withLock {
							val queue = getOrCreateQueue(guildId)
							tracks.drop(1).forEach {
								queue.addTrack(it)
								bot.logger.debug { "Added to queue: ${it.info.title}" }
							}
						}
					}
				}

				is LoadResult.SearchResult -> {
					val tracks = result.data.tracks
					if (tracks.isNotEmpty()) {
						val track = tracks.first()
						bot.logger.info { "Found track from search: ${track.info.title}" }
						respond("🎵 Found: **${track.info.title}** by **${track.info.author}**")
						playOrEnqueueTrack(guildId, track, textChannelId)
					}
				}

				is LoadResult.NoMatches -> {
					bot.logger.warn { "No matches found for query: $query" }
					respond("❌ No matches found for your search!")
				}

				is LoadResult.LoadFailed -> {
					bot.logger.error { "Failed to load track: ${result.data.message}" }
					respond("❌ Failed to load track: ${result.data.message}")
				}
			}
		} catch (e: Exception) {
			bot.logger.error(e) { "Error loading track for query: $query" }
			respond("❌ An error occurred while loading the track")
		}
	}

	override suspend fun setup() {
		// Initialize Lavalink nodes
		lavalink.addNode("ws://lavalink.pericsq.ro:4499", "plamea", name = "Node 1")
		lavalink.addNode("ws://lavalink.jirayu.net:13592", "youshallnotpass", name = "Node 2")
		bot.logger.info { "Lavalink initialized" }

		// Play command (URL or search)
		ephemeralSlashCommand(::PlayArguments) {
			name = Translations.Music.Commands.Play.name
			description = Translations.Music.Commands.Play.description

			action {
				val guildId = guild!!.id.value
				val voiceState = member!!.getVoiceState()
				val channelId = voiceState.channelId

				if (channelId == null) {
					respond {
						content = Translations.Music.Commands.Play.noVoiceChannel
							.withContext(this@action)
							.translate()
					}
					return@action
				}

				bot.logger.info { "Play command invoked for guild $guildId with query: ${arguments.query}" }

				// Connect to voice channel
				lavalink.getLink(guildId).connectAudio(channelId.value)

				// Process query
				val query = arguments.query
				val searchQuery = if (query.startsWith("http")) query else "ytsearch:$query"

				// Get the text channel ID where the command was invoked
				val textChannelId = channel.id.value

				loadAndHandleTrack(guildId, searchQuery, textChannelId) { content ->
					respond { this.content = content }
				}
			}
		}

		// Play file command
		ephemeralSlashCommand(::PlayFileArguments) {
			name = Translations.Music.Commands.Playfile.name
			description = Translations.Music.Commands.Playfile.description

			action {
				val guildId = guild!!.id.value
				val voiceState = member!!.getVoiceState()
				val channelId = voiceState.channelId

				if (channelId == null) {
					respond {
						content = Translations.Music.Commands.Play.noVoiceChannel
							.withContext(this@action)
							.translate()
					}
					return@action
				}

				bot.logger.info { "Play file command invoked for guild $guildId" }

				// Connect to voice channel
				lavalink.getLink(guildId).connectAudio(channelId.value)

				// Get the text channel ID where the command was invoked
				val textChannelId = channel.id.value

				// Load file
				loadAndHandleTrack(guildId, arguments.songfile.url, textChannelId) { content ->
					respond { this.content = content }
				}
			}
		}

		// Queue command
		ephemeralSlashCommand {
			name = Translations.Music.Commands.Queue.name
			description = Translations.Music.Commands.Queue.description

			action {
				val guildId = guild!!.id.value
				val queue = guildQueues[guildId]

				if (queue == null || (queue.isEmpty() && !queue.isPlaying)) {
					respond { content = "📭 The queue is empty!" }
					return@action
				}

				val currentTrackText = queue.currentTrack?.let { track ->
					"🎵 **Currently Playing:** ${track.info.title} by ${track.info.author}\n\n"
				} ?: ""

				if (queue.isEmpty()) {
					respond { content = "${currentTrackText}📭 No tracks in queue." }
					return@action
				}

				val queueList = queue.tracks.take(10).mapIndexed { index, track ->
					"${index + 1}. **${track.info.title}** by ${track.info.author}"
				}.joinToString("\n")

				val totalTracks = queue.size()
				val remainingText = if (totalTracks > 10) "\n... and ${totalTracks - 10} more tracks" else ""

				respond {
					content = "${currentTrackText}🎵 **Queue** ($totalTracks tracks):\n$queueList$remainingText"
				}
			}
		}

		// Clear queue command
		ephemeralSlashCommand {
			name = Translations.Music.Commands.Clear.name
			description = Translations.Music.Commands.Clear.description

			action {
				val guildId = guild!!.id.value
				val mutex = getMutex(guildId)

				mutex.withLock {
					val queue = guildQueues[guildId]
					if (queue != null && !queue.isEmpty()) {
						val clearedCount = queue.size()
						queue.clear()
						respond { content = "🗑️ Cleared $clearedCount tracks from the queue!" }
					} else {
						respond { content = "📭 The queue is already empty!" }
					}
				}
			}
		}

		// Skip command
		ephemeralSlashCommand {
			name = Translations.Music.Commands.Skip.name
			description = Translations.Music.Commands.Skip.description

			action {
				val guildId = guild!!.id.value
				val mutex = getMutex(guildId)

				mutex.withLock {
					val queue = guildQueues[guildId]
					if (queue == null || !queue.isPlaying) {
						respond { content = "❌ No music is currently playing!" }
						return@action
					}

					skipTrack(guildId)
					respond { content = "⏭️ Skipped current track!" }
				}
			}
		}
	}

	// Arguments classes
	inner class PlayArguments : Arguments() {
		val query by string {
			name = Translations.Music.Arguments.Query.name
			description = Translations.Music.Arguments.Query.description
		}
	}

	inner class PlayFileArguments : Arguments() {
		val songfile by attachment {
			name = Translations.Music.Arguments.Songfile.name
			description = Translations.Music.Arguments.Songfile.description
		}
	}
}
