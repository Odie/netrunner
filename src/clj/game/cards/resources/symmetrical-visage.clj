(in-ns 'game.core)

(def card-definitions-resources-symmetrical-visage
  {"Symmetrical Visage"
   {:events {:runner-click-draw {:req (req (genetics-trigger? state side :runner-click-draw))
                                 :msg "gain 1 [Credits]"
                                 :effect (effect (gain :credit 1))}}}})