(ns retroact.debug)

(def debug (let [retroact-debug (System/getenv "RETROACT_DEBUG")]
             (or
               (and retroact-debug (Boolean/valueOf retroact-debug))
               (Boolean/valueOf (System/getProperty "retroact.debug" "false")))))