(in-ns 'game.core)

(def card-definitions-events-bribery
  {"Bribery"
   {:prompt "How many credits?"
    :choices :credit
    :msg (msg "increase the rez cost of the first unrezzed ICE approached by " target " [Credits]")
    :effect (effect (resolve-ability (run-event) card nil))}})