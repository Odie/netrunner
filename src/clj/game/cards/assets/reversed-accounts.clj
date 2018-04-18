(in-ns 'game.core)

(def card-definitions-assets-reversed-accounts
  {"Reversed Accounts"
   {:advanceable :always
    :abilities [{:cost [:click 1]
                 :label "Force the Runner to lose 4 [Credits] per advancement"
                 :msg (msg "force the Runner to lose " (min (* 4 (get card :advance-counter 0)) (:credit runner)) " [Credits]")
                 :effect (effect (lose :runner :credit (* 4 (get card :advance-counter 0))) (trash card))}]}})