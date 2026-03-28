Feature: DWIM Reader Selection
  Pure "Do What I Mean" selection from a PC/SC reader snapshot.
  Cascade: explicit hint > environment hint > auto-pick single > fail on ambiguity.
  Fragments (hint or ignore) must be at least 3 characters.

  # --- Auto-pick ---

  Scenario: Auto-picks the single reader
    Given readers:
      | name           | present |
      | Contact Reader | yes     |
    When I pick a reader
    Then "Contact Reader" is selected

  Scenario: Auto-picks the single reader even without a card
    Given readers:
      | name           | present |
      | Contact Reader | no      |
    When I pick a reader
    Then "Contact Reader" is selected

  Scenario: Auto-picks the only reader with a card
    Given readers:
      | name             | present |
      | Empty Reader     | no      |
      | Reader With Card | yes     |
      | Other Empty      | no      |
    When I pick a reader
    Then "Reader With Card" is selected

  # --- Hint selection ---

  Scenario: Selects reader by 1-indexed number
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | yes     |
      | Reader C | yes     |
    When I use hint "2"
    Then "Reader B" is selected

  Scenario: Selects reader by name fragment
    Given readers:
      | name          | present |
      | ACS ACR122U   | yes     |
      | YubiKey 5 NFC | yes     |
    When I use hint "Yubi"
    Then "YubiKey 5 NFC" is selected

  Scenario: Matches hint case-insensitively
    Given readers:
      | name          | present |
      | ACS ACR122U   | yes     |
      | YubiKey 5 NFC | yes     |
    When I use hint "yubikey"
    Then "YubiKey 5 NFC" is selected

  Scenario: Three-digit number treated as name fragment
    Given readers:
      | name          | present |
      | Model 100     | yes     |
      | YubiKey 5 NFC | yes     |
    When I use hint "100"
    Then "Model 100" is selected

  Scenario: Hint shorter than three characters is ignored
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | no      |
    When I use hint "Re"
    Then "Reader A" is selected
    And message contains "Reader A"

  Scenario: Hint selects reader even without a card
    Given readers:
      | name     | present |
      | Reader A | no      |
      | Reader B | yes     |
    When I use hint "Reader A"
    Then "Reader A" is selected

  # --- Fallback ---
  # When an explicit hint doesn't match, DWIM emits a message and falls back
  # to card-present reader selection rather than failing.

  Scenario: Empty hint treated as no hint
    Given readers:
      | name           | present |
      | Contact Reader | yes     |
    When I use hint ""
    Then "Contact Reader" is selected

  Scenario: Falls back to card-present reader when hint doesn't match
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | no      |
    When I use hint "NonExistent"
    Then "Reader A" is selected
    And message contains "Reader A"

  Scenario: Index zero falls back to card-present reader
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | no      |
    When I use hint "0"
    Then "Reader A" is selected
    And message contains "Reader A"

  Scenario: Index out of bounds falls back to card-present reader
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | no      |
    When I use hint "99"
    Then "Reader A" is selected
    And message contains "Reader A"

  # --- Ignore ---

  Scenario: Ignores reader by name fragment
    Given readers:
      | name               | present |
      | Virtual Smart Card | yes     |
      | Contact Reader     | yes     |
    When I ignore "Virtual"
    Then "Contact Reader" is selected

  Scenario: Multiple ignore fragments filter independently
    Given readers:
      | name               | present |
      | Virtual Smart Card | yes     |
      | Contact Reader     | yes     |
      | YubiKey 5 NFC      | yes     |
    When I ignore "Virtual"
    And I ignore "Contact"
    Then "YubiKey 5 NFC" is selected

  Scenario: Ignore fragment shorter than three characters has no effect
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | yes     |
    When I ignore "Re"
    Then selection fails

  Scenario: Explicit hint overrides ignore
    Given readers:
      | name               | present |
      | Virtual Smart Card | yes     |
      | Contact Reader     | yes     |
    When I ignore "Virtual"
    And I use hint "Virtual"
    Then "Virtual Smart Card" is selected

  # --- Environment ---

  Scenario: Selects reader from environment hint
    Given readers:
      | name          | present |
      | ACS ACR122U   | yes     |
      | YubiKey 5 NFC | yes     |
    And environment "READER" is "Yubi"
    When I use env hint "READER" and ignore "IGNORE"
    Then "YubiKey 5 NFC" is selected

  Scenario: Explicit hint overrides environment hint
    Given readers:
      | name          | present |
      | ACS ACR122U   | yes     |
      | YubiKey 5 NFC | yes     |
    And environment "READER" is "Yubi"
    When I use env hint "READER" and ignore "IGNORE"
    And I use hint "ACS"
    Then "ACS ACR122U" is selected

  # --- Exclusive readers ---

  Scenario: Auto-pick selects non-exclusive reader
    Given readers:
      | name             | present | exclusive |
      | Exclusive Reader | yes     | yes       |
      | Available Reader | yes     | no        |
    When I pick a reader
    Then "Available Reader" is selected

  Scenario: Hint selects exclusive reader explicitly
    Given readers:
      | name             | present | exclusive |
      | Shared Reader    | yes     | no        |
      | Exclusive Reader | yes     | yes       |
    When I use hint "Exclusive"
    Then "Exclusive Reader" is selected

  # --- Failure cases ---

  Scenario: Fails when multiple readers have cards and no hint
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | yes     |
    When I pick a reader
    Then selection fails

  Scenario: Fails when the only reader is ignored
    Given readers:
      | name           | present |
      | Contact Reader | yes     |
    When I ignore "Contact"
    Then selection fails

  Scenario: Fails when all readers are ignored
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | yes     |
    When I ignore "Reader"
    Then selection fails

  Scenario: Fails on empty reader list
    Given no readers
    When I pick a reader
    Then selection fails

  Scenario: Fails when hint matches multiple readers
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader B | yes     |
    When I use hint "Reader"
    Then selection fails

  Scenario: Fails when only exclusive readers have cards
    Given readers:
      | name           | present | exclusive |
      | Shared Reader  | no      | no        |
      | Exclusive Card | yes     | yes       |
    When I pick a reader
    Then selection fails

  Scenario: Fails when identically-named readers both have cards
    Given readers:
      | name     | present |
      | Reader A | yes     |
      | Reader A | yes     |
    When I pick a reader
    Then selection fails
