package HellBot.extensions


import dev.kord.core.behavior.reply
import dev.kord.core.event.message.MessageCreateEvent
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import dev.kordex.core.utils.respond
import kotlin.random.Random

class ReactionExtension : Extension() {
	/**
	 * The name of the extension, used for identification and loading.
	 */
	override val name = "react"

	/**
	 * A brief description of the extension, explaining its purpose.
	 */
	fun chance(probability: Double): Boolean {
		require(probability in 0.0..1.0) { "Probability must be between 0.0 and 1.0" }
		return Random.nextDouble() < probability
	}

	override suspend fun setup() {
		event<MessageCreateEvent> {
			action {
				if(event.message.author?.id!!.value.toLong() == 227428836248256514) {
					if(chance(0.2)) {
						val replies = arrayOf("HACKER!!",
							"Ruhizzz, hacker!",
							"Rocket league ruiz?? Pot?",
							"Gast",
							"Ruiz Juan-Miguel Roger Segond von Banchet",
							"Wil je een blauwe?",
							"Ruiz, ik heb je IP-adres",
							"Ruiz, ik heb je adres",
							"Sax?",
							"Ruiz, jij mag op mijn verjaardag komen")
						val randomIndex = Random.nextInt(replies.size);
						event.message.respond {
							content = replies[randomIndex]
						}
					}
				}
				else if(event.message.author!! != bot.kordRef.getSelf()) {

					val cont: String = event.message.content.lowercase()

					with(cont){
						when {
							cont.contains("hallo") -> {
								if(chance(0.2)){
									event.message.reply { content = "HALO!" }
								}
							}

							cont.contains("ben") -> {
								if(chance(0.2)){
									event.message.reply{ content = "BEN?!?!?!?!?!?!?!"}
								}
							}

						}
					}

				}

			}
		}

	}

}
