(in-ns 'game.core)

(def card-definitions-operations-special-report
  {"Special Report"
   {:prompt "Select any number of cards in HQ to shuffle into R&D"
    :choices {:max (req (count (:hand corp)))
              :req #(and (= (:side %) "Corp")
                         (in-hand? %))}
    :msg (msg "shuffle " (count targets) " cards in HQ into R&D and draw " (count targets) " cards")
    :delayed-completion true
    :effect (req (doseq [c targets]
                   (move state side c :deck))
                 (shuffle! state side :deck)
                 (draw state side eid (count targets) nil))}})