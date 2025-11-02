(ns dev.freeformsoftware.ejabberd.room-membership
  "Functions for computing room membership and affiliations.
   
   This namespace provides utilities for determining which users belong to
   which rooms and what their affiliation level should be based on group membership."
  (:require
   [clojure.set :as set]))

(defn compute-room-affiliation
  "Computes the appropriate affiliation for a user in a room.
   
   Parameters:
   - user-groups: Set of group keywords the user belongs to
   - room-admins: Set of group keywords that grant admin privileges
   - room-members: Set of group keywords that grant member access
   
   Returns:
   - 'admin' if user's groups intersect with room's :admins
   - 'member' if user's groups intersect with room's :members (but not :admins)
   - 'none' if no intersection"
  [user-groups room-admins room-members]
  (cond
    (seq (set/intersection user-groups room-admins))  "admin"
    (seq (set/intersection user-groups room-members)) "member"
    :else                                             "none"))

(defn get-room-members-with-affiliations
  "Gets all members who have access to a room along with their affiliations.
   
   Parameters:
   - room: Room definition map with :admins and :members (sets of group keywords)
   - all-members: Vector of all member maps with :user-id, :name, and :groups
   - xmpp-domain: The XMPP domain (e.g., 'example.org') for constructing JIDs
   
   Returns:
   Vector of maps with :user-id, :name, :jid, and :affiliation for users who have access.
   Users with 'none' affiliation are filtered out.
   JID is constructed as user-id@xmpp-domain.
   
   Example return:
   [{:user-id \"alice\" :name \"Alice Smith\" :jid \"alice@example.org\" :affiliation \"admin\"}
    {:user-id \"bob\" :name \"Bob Jones\" :jid \"bob@example.org\" :affiliation \"member\"}]"
  [room all-members xmpp-domain]
  (let [room-admins  (:admins room)
        room-members (:members room)]
    (->> all-members
         (map (fn [member]
                (let [user-groups (:groups member)
                      affiliation (compute-room-affiliation user-groups room-admins room-members)]
                  (when (not= affiliation "none")
                    {:user-id     (:user-id member)
                     :name        (:name member)
                     :jid         (str (:user-id member) "@" xmpp-domain)
                     :affiliation affiliation}))))
         (remove nil?)
         vec)))
