(in-ns 'game.core)

(def card-definitions-ice-checkpoint
  {"Checkpoint"
   {:effect take-bad-pub
    :subroutines [(trace-ability 5 {:label "Do 3 meat damage when this run is successful"
                                    :msg "do 3 meat damage when this run is successful"
                                    :effect (effect (register-events
                                                      {:successful-run
                                                       {:delayed-completion true
                                                        :msg "do 3 meat damage"
                                                        :effect (effect (damage eid :meat 3 {:card card}))}
                                                       :run-ends {:effect (effect (unregister-events card))}}
                                                     card))})]
    :events {:successful-run nil :run-ends nil}}})