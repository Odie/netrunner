(in-ns 'game.core)

(def card-definitions-events-on-the-lam
  {"On the Lam"
   {:req (req (some #(is-type? % "Resource") (all-active-installed state :runner)))
    :prompt "Choose a resource to host On the Lam"
    :choices {:req #(and (is-type? % "Resource")
                         (installed? %))}
    :effect (effect (host target (assoc card :zone [:discard]))
                    (system-msg (str "hosts On the Lam on " (:title target))))
    :prevent {:tag [:all] :damage [:meat :net :brain]}
    :abilities [{:label "[Trash]: Avoid 3 tags"
                 :msg "avoid up to 3 tags"
                 :effect (effect (tag-prevent 3) (trash card {:cause :ability-cost}))}
                {:label "[Trash]: Prevent up to 3 damage"
                 :msg "prevent up to 3 damage"
                 :effect (effect (damage-prevent :net 3)
                                 (damage-prevent :meat 3)
                                 (damage-prevent :brain 3)
                                 (trash card {:cause :ability-cost}))}]}})