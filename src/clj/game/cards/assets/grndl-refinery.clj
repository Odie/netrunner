(in-ns 'game.core)

(def card-definitions-assets-grndl-refinery
  {"GRNDL Refinery"
   {:advanceable :always
    :abilities [{:label "Gain 4 [Credits] for each advancement token on GRNDL Refinery"
                 :cost [:click 1] :msg (msg "gain " (* 4 (get card :advance-counter 0)) " [Credits]")
                 :effect (effect (trash card) (gain :credit (* 4 (get card :advance-counter 0))))}]}})