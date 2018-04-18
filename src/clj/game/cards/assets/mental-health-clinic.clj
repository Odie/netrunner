(in-ns 'game.core)

(def card-definitions-assets-mental-health-clinic
  {"Mental Health Clinic"
   (let [ability {:msg "gain 1 [Credits]"
                  :label "Gain 1 [Credits] (start of turn)"
                  :once :per-turn
                  :effect (effect (gain :credit 1))}]
     {:effect (effect (gain :runner :hand-size-modification 1))
      :leave-play (effect (lose :runner :hand-size-modification 1))
      :derezzed-events {:runner-turn-ends corp-rez-toast}
      :events {:corp-turn-begins ability}
      :abilities [ability]})})