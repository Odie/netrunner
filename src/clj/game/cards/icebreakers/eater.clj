(in-ns 'game.core)

(def card-definitions-icebreakers-eater
  {"Eater"
   (auto-icebreaker ["All"]
                    {:abilities [{:cost [:credit 1]
                                  :msg "break ICE subroutine and access 0 cards this run"
                                  :effect (effect (max-access 0))}
                                 (strength-pump 1 1)]})})