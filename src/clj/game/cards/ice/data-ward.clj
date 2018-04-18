(in-ns 'game.core)

(def card-definitions-ice-data-ward
  {"Data Ward"
   {:runner-abilities [{:label "Pay 3 [Credits]"
                        :effect (req (pay state :runner card :credit 3)
                                     (system-msg state :runner "chooses to pay 3 [Credits] on encountering Data Ward"))}
                       {:label "Take 1 tag"
                        :delayed-completion true
                        :effect (req (system-msg state :runner "chooses to take 1 tag on encountering Data Ward")
                                     (tag-runner state :runner eid 1))}]
    :subroutines [end-the-run-if-tagged]}})
