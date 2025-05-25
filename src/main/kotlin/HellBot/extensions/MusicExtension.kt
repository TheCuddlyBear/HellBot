package HellBot.extensions

import HellBot.extensions.MusicExtension.PlayArguments
import HellBot.i18n.Translations
import dev.arbjerg.lavalink.protocol.v4.LoadResult
import dev.arbjerg.lavalink.protocol.v4.Track
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.commands.converters.impl.user
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.core.i18n.withContext
import dev.schlaubi.lavakord.audio.TrackEndEvent
import dev.schlaubi.lavakord.audio.on
import dev.schlaubi.lavakord.kord.lavakord
import dev.schlaubi.lavakord.plugins.lavasrc.LavaSrc
import dev.schlaubi.lavakord.plugins.sponsorblock.Sponsorblock
import dev.schlaubi.lavakord.rest.loadItem
import java.util.Queue

class MusicExtension() : Extension() {
	override val name = "music"

	val lavalink = this.bot.kordRef.lavakord {
		plugins {
			install(LavaSrc)
			install(Sponsorblock)
		}
	}

	val Map = LinkedHashMap<ULong, ArrayDeque<Track>>()

	suspend fun playOrEnqueueSong(guildId: ULong, track: Track) {
		val queue = Map.get(guildId)
		if(queue == null) {
			Map[guildId] = ArrayDeque<Track>().apply { add(track) }
			lavalink.getLink(guildId).player.playTrack(Map[guildId]!!.removeFirst())
		}else {
			queue.add(track)
		}
	}

	override suspend fun setup() {

		lavalink.addNode("ws://lavalink.pericsq.ro:4499", "plamea", name="Node 1")
		lavalink.addNode("ws://dnode2.astrast.host:9869", "https://discord.gg/8M2bAHZaQH", name="Node 2")
		bot.logger.info { "Lavalink initialized" }

		publicSlashCommand(::PlayArguments) {
			name = Translations.Music.Commands.Play.name
			description = Translations.Music.Commands.Play.description
			action {

				val link = lavalink.getLink(guild!!.id.value)
				val player = link.player

				player.on<TrackEndEvent> {
					if (Map.containsKey(guild!!.id.value)) {
						val queue = Map[guild!!.id.value]!!
						if (queue.isNotEmpty()) {
							player.playTrack(queue.removeFirst())
						} else {
							Map.remove(guild!!.id.value)
						}
					}
				}

				val voiceState = member!!.getVoiceState()
				val channelId = voiceState.channelId
				if(channelId != null){
					link.connectAudio(channelId.value)

					val query = arguments.query
					val search = if(query.startsWith("https://") || query.startsWith("http://")) {
						query
					} else {
						"ytsearch:$query"
					}

					when (val item = link.loadItem(search)) {
						is LoadResult.TrackLoaded -> playOrEnqueueSong(guild!!.id.value, item.data)
						is LoadResult.PlaylistLoaded ->  playOrEnqueueSong(guild!!.id.value, item.data.tracks.first())
						is LoadResult.SearchResult -> playOrEnqueueSong(guild!!.id.value, item.data.tracks.first())
						is LoadResult.NoMatches -> respond { content = "No matches found!"}
						is LoadResult.LoadFailed -> respond { content = "Failed to load track: ${item.data.message}" }
					}

					respond {
						content = Translations.Music.Commands.Play.response
							.withContext(this@action)
							.translateNamed(
								"title" to player.playingTrack?.info?.title,
								"author" to player.playingTrack?.info?.author
							)
					}
				}else{
					respond {
						content = Translations.Music.Commands.Play.noVoiceChannel
							.withContext(this@action)
							.translate()
					}
				}
			}
		}
	}

	inner class PlayArguments : Arguments() {
		val query by string {
			name = Translations.Music.Arguments.Query.name
			description = Translations.Music.Arguments.Query.description
		}
	}


}
