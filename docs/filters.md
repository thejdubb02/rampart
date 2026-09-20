# Filters

Rules are Sieve. They live on the mail server and run at delivery, so they work with
Rampart closed. Nothing in Rampart evaluates a rule.

The format is not ours. Bulwark writes its own rule model as JSON in a comment at the top
of the script and generates the Sieve underneath it, and Rampart reads and writes that
same format so neither client destroys the other's rules. `Sieve.kt` has the detail,
including the two things it will not do: it never rebuilds a rule it does not fully
understand, and it never touches part of a script that no builder wrote.

## Two places a rule can live

**Per account.** The account's own script, on its own server. This is the ordinary case.

**Everywhere.** A set kept for every account at once, edited in one place.

The second is not a second kind of filter. A global rule is compiled into each account's
script and saved to each account's server, exactly like an account's own rule, so it runs
at delivery on every account without Rampart being open. Global rules are written first in
the script, because Sieve runs top down and a rule that stops processing has to be able to
mean it.

Each rule carries `"global": true` in the metadata. Without that mark a set pushed to three
accounts reads back as three unrelated account rules, and could then never be changed or
removed again: every later save would keep them and add the global copy beside them.

## The local file, and why there is one

`filters.json` beside the accounts file holds the set as it is meant to be, plus the
accounts it is deliberately kept off. The servers hold copies; this holds the intent.

Without it there is no authority. Removing a rule means guessing which of three servers is
right, and an account that was offline on the day of a change keeps a rule nobody can see
any more. Every save pushes the whole set out again, so drift is corrected rather than
accumulated.

A fresh install on another machine has no copy of it. That is why the mark in the metadata
matters: the first time an account's filters are read, a set found there is adopted, but
only when there is nothing here yet. After that the local file wins and a server that
disagrees is drift.

## Opting an account out

The setting is a list of exceptions, not a list of accounts to include, so an account
signed in tomorrow gets the set without anybody going back to a screen. Switching an
account off pushes to it as well: the rules that were pushed last time have to be removed,
and a switch that changes nothing on the server would be a lie.

## What cannot carry the set

Two cases, both reported rather than swallowed:

- A server with no Sieve.
- An account whose filter script was written by hand rather than by a builder. Rampart
  cannot rebuild one of those without guessing at what it said, so it leaves it alone and
  shows it as text.
