(in-ns 'game.core)

(def card-definitions-events-game-day
  {"Game Day"
   {:msg (msg "draw " (- (hand-size state :runner) (count (:hand runner))) " cards")
    :effect (effect (draw (- (hand-size state :runner) (count (:hand runner)))))}})