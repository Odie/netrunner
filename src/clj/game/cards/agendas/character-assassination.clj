(in-ns 'game.core)

(def card-definitions-agendas-character-assassination
  {"Character Assassination"
   {:prompt "Select a resource to trash"
    :choices {:req #(and (installed? %)
                         (is-type? % "Resource"))}
    :msg (msg "trash " (:title target))
    :interactive (req true)
    :delayed-completion true
    :effect (effect (trash eid target {:unpreventable true}))}})