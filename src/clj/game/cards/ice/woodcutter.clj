(in-ns 'game.core)

(def card-definitions-ice-woodcutter
  {"Woodcutter"
   {:advanceable :while-rezzed
    :abilities [{:label "Gain subroutines"
                 :msg (msg "gain " (:advance-counter card 0) " subroutines")}]
    :subroutines [(do-net-damage 1)]}})