(in-ns 'game.core)

(def card-definitions-agendas-oaktown-renovation
  {"Oaktown Renovation"
   {:install-state :face-up
    :events {:advance {:req (req (= (:cid card) (:cid target)))
                       :msg (msg "gain " (if (>= (:advance-counter (get-card state card)) 5) "3" "2") " [Credits]")
                       :effect (req (gain state side :credit
                                          (if (>= (:advance-counter (get-card state card)) 5) 3 2)))}}}})