(ns dev.freeformsoftware.db.util
  "Shared database utilities.")

(set! *warn-on-reflection* true)

(defn generate-random-password
  "Generates a random base64-encoded password.
   Creates 24 random bytes and encodes them as base64 for a secure temporary password."
  []
  (let [random-bytes (byte-array 24)]
    (.nextBytes (java.security.SecureRandom.) random-bytes)
    (.encodeToString (java.util.Base64/getEncoder) random-bytes)))
