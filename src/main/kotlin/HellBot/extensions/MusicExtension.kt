package HellBot.extensions

import HellBot.extensions.MusicExtension.PlayArguments
import HellBot.i18n.Translations
import dev.arbjerg.lavalink.protocol.v4.LoadResult
import dev.arbjerg.lavalink.protocol.v4.Track
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import dev.kord.core.entity.interaction.followup.EphemeralFollowupMessage
import dev.kord.core.entity.interaction.followup.FollowupMessage
import dev.kord.rest.builder.message.embed
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.converters.impl.attachment
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.commands.converters.impl.user
import dev.kordex.core.components.components
import dev.kordex.core.components.publicButton
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.i18n.withContext
import dev.schlaubi.lavakord.audio.TrackEndEvent
import dev.schlaubi.lavakord.audio.TrackStartEvent
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
	val MessageMap = LinkedHashMap<ULong, Message>()

	suspend fun playOrEnqueueSong(guildId: ULong, track: Track, message: Message) {
		val queue = Map.get(guildId)
		if(queue == null) {
			Map[guildId] = ArrayDeque<Track>().apply { add(track) }
			val channel = message.getChannel()
			val newMes = channel.createMessage {
				content = "Playing song..."
			}
			MessageMap[guildId] = newMes

			val player = lavalink.getLink(guildId).player
			player.on<TrackStartEvent> {
				val map = MessageMap[guildId]
				if (map != null) {
					val channel = map.getChannel()
					map.delete()
					val newMes = channel.createMessage {
						embed {
							author { "Music Controller" }
							thumbnail { url = track.info.artworkUrl ?: "https://cdn.discordapp.com/embed/avatars/0.png" }
							field {
								name = "Now playing"
								value = track.info.title.toString()
							}

							field{
								name = "Author"
								value = track.info.author.toString()
							}

							footer {
								text = "Playing on node: ${lavalink.getLink(guildId).node.name}"
							}

						}

						components {
							publicButton {
								label = Translations.Music.Buttons.Pause.name
								style= ButtonStyle.Primary

								action {
									val player = lavalink.getLink(guildId).player
									if(player.paused) {
										player.unPause()
									} else{
										player.pause()
									}
								}
							}

							publicButton {
								label = Translations.Music.Buttons.Next.name
								style= ButtonStyle.Primary

								action {
									val player = lavalink.getLink(guildId).player
									player.stopTrack()
								}
							}

							publicButton {
								label = Translations.Music.Buttons.Stop.name
								style= ButtonStyle.Danger

								action {
									lavalink.getLink(guildId).destroy()
								}
							}
						}
					}
					MessageMap[guildId] = newMes
				}
			}

			player.playTrack(Map[guildId]!!.removeFirst())
		}else {
			queue.add(track)
		}
	}

	override suspend fun setup() {

		lavalink.addNode("ws://lavalink.pericsq.ro:4499", "plamea", name="Node 1")
		lavalink.addNode("ws://lavahatry4.techbyte.host:3000", "NAIGLAVA-dash.techbyte.host", name="Node 2")
		bot.logger.info { "Lavalink initialized" }

		ephemeralSlashCommand(::PlayArguments) {
			name = Translations.Music.Commands.Play.name
			description = Translations.Music.Commands.Play.description
			action {

				val link = lavalink.getLink(guild!!.id.value)
				val player = link.player

				player.on<TrackEndEvent> {
					if (Map.containsKey(guild!!.id.value)) {
						val queue = Map[guild!!.id.value]!!
						if (queue.isNotEmpty()) {
							val track = queue.removeFirst()
							player.playTrack(track)
						} else {
							Map.remove(guild!!.id.value)
							MessageMap.remove(guild!!.id.value)
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
						is LoadResult.TrackLoaded -> {

							val mes = respond {
								content = Translations.Music.Commands.Play.response
									.withContext(this@action)
									.translateNamed(
										"title" to item.data.info.title,
										"author" to item.data.info.author
									)
							}

							playOrEnqueueSong(guild!!.id.value, item.data, mes.message)
						}
						is LoadResult.PlaylistLoaded ->  {
							val mes = respond {
								content = Translations.Music.Commands.Play.response
									.withContext(this@action)
									.translateNamed(
										"title" to item.data.tracks.first().info.title,
										"author" to item.data.tracks.first().info.author
									)
							}

							playOrEnqueueSong(guild!!.id.value, item.data.tracks.first(), mes.message)
						}
						is LoadResult.SearchResult -> {
							val mes = respond {
								content = Translations.Music.Commands.Play.response
									.withContext(this@action)
									.translateNamed(
										"title" to item.data.tracks.first().info.title,
										"author" to item.data.tracks.first().info.author
									)
							}
							playOrEnqueueSong(guild!!.id.value, item.data.tracks.first(), mes.message)
						}
						is LoadResult.NoMatches -> respond { content = "No matches found!"}
						is LoadResult.LoadFailed -> respond { content = "Failed to load track: ${item.data.message}" }
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

		ephemeralSlashCommand(::PlayFileArguments) {
			name = Translations.Music.Commands.Playfile.name
			description = Translations.Music.Commands.Playfile.description

			action {

				val link = lavalink.getLink(guild!!.id.value)
				val player = link.player

				player.on<TrackEndEvent> {
					if (Map.containsKey(guild!!.id.value)) {
						val queue = Map[guild!!.id.value]!!
						if (queue.isNotEmpty()) {
							val track = queue.removeFirst()
							player.playTrack(track)
						} else {
							Map.remove(guild!!.id.value)
							MessageMap.remove(guild!!.id.value)
						}
					}
				}

				val voiceState = member!!.getVoiceState()
				val channelId = voiceState.channelId
				if(channelId != null){
					link.connectAudio(channelId.value)

					val query = arguments.songfile.url

					when (val item = link.loadItem(query)) {
						is LoadResult.TrackLoaded -> {

							val mes = respond {
								content = Translations.Music.Commands.Play.response
									.withContext(this@action)
									.translateNamed(
										"title" to item.data.info.title,
										"author" to item.data.info.author
									)
							}

							playOrEnqueueSong(guild!!.id.value, item.data, mes.message)
						}
						is LoadResult.PlaylistLoaded ->  {
							val mes = respond {
								content = Translations.Music.Commands.Play.response
									.withContext(this@action)
									.translateNamed(
										"title" to item.data.tracks.first().info.title,
										"author" to item.data.tracks.first().info.author
									)
							}

							playOrEnqueueSong(guild!!.id.value, item.data.tracks.first(), mes.message)
						}
						is LoadResult.SearchResult -> {
							val mes = respond {
								content = Translations.Music.Commands.Play.response
									.withContext(this@action)
									.translateNamed(
										"title" to item.data.tracks.first().info.title,
										"author" to item.data.tracks.first().info.author
									)
							}
							playOrEnqueueSong(guild!!.id.value, item.data.tracks.first(), mes.message)
						}
						is LoadResult.NoMatches -> respond { content = "No matches found!"}
						is LoadResult.LoadFailed -> respond { content = "Failed to load track: ${item.data.message}" }
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

	inner class PlayFileArguments : Arguments() {
		val songfile by attachment {
			name = Translations.Music.Arguments.Songfile.name
			description = Translations.Music.Arguments.Songfile.description
		}
	}

}
