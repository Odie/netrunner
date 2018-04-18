(in-ns 'game.core)

(def card-definitions-events-system-outage
  {"System Outage"
   {:events {:corp-draw {:req (req (not (first-event? state side :corp-draw)))
                         :msg "force the Corp to lose 1 [Credits]"
                         :effect (effect (lose :corp :credit 1))}}}})