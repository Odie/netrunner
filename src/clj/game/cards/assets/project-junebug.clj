(in-ns 'game.core)

(def card-definitions-assets-project-junebug
  {"Project Junebug"
   (advance-ambush 1 {:req (req (< 0 (:advance-counter (get-card state card) 0)))
                      :msg (msg "do " (* 2 (:advance-counter (get-card state card) 0)) " net damage")
                      :delayed-completion true
                      :effect (effect (damage eid :net (* 2 (:advance-counter (get-card state card) 0))
                                              {:card card}))})})