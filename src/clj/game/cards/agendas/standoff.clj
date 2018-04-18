(in-ns 'game.core)

(def card-definitions-agendas-standoff
  {"Standoff"
   (letfn [(stand [side]
             {:delayed-completion true
              :prompt "Choose one of your installed cards to trash due to Standoff"
              :choices {:req #(and (installed? %)
                                   (same-side? side (:side %)))}
              :cancel-effect (req (if (= side :runner)
                                    (do (draw state :corp)
                                        (gain state :corp :credit 5)
                                        (clear-wait-prompt state :corp)
                                        (system-msg state :runner "declines to trash a card due to Standoff")
                                        (system-msg state :corp "draws a card and gains 5 [Credits] from Standoff")
                                        (effect-completed state :corp eid))
                                    (do (system-msg state :corp "declines to trash a card from Standoff")
                                        (clear-wait-prompt state :runner)
                                        (effect-completed state :corp eid))))
              :effect (req (do (system-msg state side (str "trashes " (card-str state target) " due to Standoff"))
                               (clear-wait-prompt state (other-side side))
                               (trash state side target {:unpreventable true})
                               (show-wait-prompt state side (str (side-str (other-side side)) " to trash a card for Standoff"))
                               (continue-ability state (other-side side) (stand (other-side side)) card nil)))})]
     {:interactive (req true)
      :delayed-completion true
      :effect (effect (show-wait-prompt (str (side-str (other-side side)) " to trash a card for Standoff"))
                      (continue-ability :runner (stand :runner) card nil))})})