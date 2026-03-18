(ns back-channeling.bot.ai)

(defprotocol AIProvider
  (chat-completion [provider messages options]
    "Send a chat completion request.
     `messages` is a vector of maps with :role and :content keys.
     `options` is a map that may include :max-tokens, :temperature, etc.
     Returns a string (the assistant's response content)."))
