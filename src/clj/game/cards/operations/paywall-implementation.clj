(in-ns 'game.core)

(def card-definitions-operations-paywall-implementation
  {"Paywall Implementation"
   {:events {:successful-run {:msg "gain 1 [Credits]" :effect (effect (gain :corp :credit 1))}}}})