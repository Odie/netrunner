(in-ns 'game.core)

(def card-definitions-events-levy-ar-lab-access
  {"Levy AR Lab Access"
   {:msg "shuffle their Grip and Heap into their Stack and draw 5 cards"
    :effect (effect (shuffle-into-deck :hand :discard) (draw 5)
                    (move (first (:play-area runner)) :rfg))}})