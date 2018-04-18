(in-ns 'game.core)

(def card-definitions-ice-tollbooth
  {"Tollbooth"
   {:implementation "Encounter effect is manual"
    :abilities [{:msg "make the Runner pay 3 [Credits], if able"
                 :effect (effect (pay :runner card :credit 3))}]
    :subroutines [end-the-run]}})