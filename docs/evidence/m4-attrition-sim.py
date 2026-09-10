#!/usr/bin/env python3
"""The tuning run behind emberdelve-ffi.6 — M4's attrition numbers.

ffi.6 was filed as a `--demo` job. It could not be one: ScriptedDiceRoller gives reproducible
dice, but there is no delve loop to run them through yet — no clocks, no rest, no site. So the
reproducible artifact is this instead: a simulator that mirrors CombatEngine.resolveAttack and
GoblinAi exactly, and answers the arithmetic questions the spec needed answered before anything
is built.

Mirrored rules, and they are the whole reason this is not a spreadsheet:
  - natural 20 -> CRIT, which AUTO-HITS regardless of AC and doubles the damage DICE only
    (AbstractDiceRoller.adjudicate yields CRIT before it ever reads dc()).
  - natural 1 -> CRIT_FAIL, an automatic miss.
  - otherwise d20 + toHit >= AC.
  - initiative is d20 + DEX, ties to the higher DEX and then to the player. The goblin's DEX +2
    beats the fighter's +1, so the goblin wins ties.
  - a creature that starts the fight away from its foe spends its first turn closing (GoblinAi
    moves OR attacks, never both).

Run it: python3 docs/evidence/m4-attrition-sim.py
Every figure quoted in emberdelve-ffi.6 and in the M4 spec comes out of this file, seeded.
"""
import random
import statistics

SEED = 20260909


class Stat:
    def __init__(s, name, ac, hp, tohit, dice_n, dice_d, dmg_mod, dex):
        s.name, s.ac, s.hp, s.tohit = name, ac, hp, tohit
        s.n, s.d, s.mod, s.dex = dice_n, dice_d, dmg_mod, dex


# Today's content, for the "before" column.
FIGHTER_12 = Stat("fighter", 16, 12, 5, 1, 8, 3, 1)
GOBLIN_OLD = Stat("goblin", 15, 7, 4, 1, 6, 2, 2)

# What ffi.6 settled.
FIGHTER = Stat("fighter", 16, 20, 5, 1, 8, 3, 1)
MOB = Stat("goblin", 12, 6, 3, 1, 4, 1, 2)      # the goblin, weakened, so packs can exist
BRUTE = Stat("brute", 13, 16, 4, 1, 8, 2, 0)    # the new stat block, 4h9.9

REST, POTION = 4, 8
TORCHES, POTIONS = 2, 2


def swing(rng, attacker, target_ac):
    nat = rng.randint(1, 20)
    if nat == 1:
        return 0
    crit = nat == 20
    if not crit and nat + attacker.tohit < target_ac:
        return 0
    dice = attacker.n * 2 if crit else attacker.n
    return sum(rng.randint(1, attacker.d) for _ in range(dice)) + attacker.mod


def fight(rng, hero, foes, start_hp, approach=True):
    """One fight to the death — exits are illegal in combat, so nobody leaves. Returns hp left."""
    hp = start_hp
    fhp = [f.hp for f in foes]
    order = [("h", rng.randint(1, 20) + hero.dex, 0)]
    order += [("f", rng.randint(1, 20) + f.dex, i) for i, f in enumerate(foes)]
    order.sort(key=lambda t: (-t[1],
                              -(foes[t[2]].dex if t[0] == "f" else hero.dex),
                              0 if t[0] == "f" else 1))
    closed = [not approach] * len(foes)
    for _ in range(40):
        for who, _, i in order:
            if who == "h":
                if hp <= 0:
                    continue
                alive = [k for k in range(len(foes)) if fhp[k] > 0]
                if not alive:
                    return hp
                fhp[alive[0]] -= swing(rng, hero, foes[alive[0]].ac)
            else:
                if fhp[i] <= 0 or hp <= 0:
                    continue
                if not closed[i]:
                    closed[i] = True          # the turn is spent closing
                    continue
                hp -= swing(rng, foes[i], hero.ac)
        if hp <= 0 or all(f <= 0 for f in fhp):
            return hp
    return hp


def survival(hero, foes, hp, n=40000, approach=True, seed=SEED):
    rng = random.Random(seed)
    return sum(1 for _ in range(n) if fight(rng, hero, foes, hp, approach) > 0) / n


def doubt_window(hero, foes, lo=0.40, hi=0.70, n=20000):
    """The hit points at which P(surviving the next room) is genuinely uncertain.

    This is what §9's gate asks for — "stand in front of a door at 5 hit points and genuinely
    not know". A window that is narrow, or that sits below 1, means the number the player is
    staring at does not inform the decision.
    """
    curve = {hp: survival(hero, foes, hp, n=n) for hp in range(1, hero.hp + 1)}
    inside = [hp for hp, p in curve.items() if lo <= p <= hi]
    fifty = min(curve, key=lambda h: abs(curve[h] - 0.5))
    return curve, fifty, inside


def delve(rng, hero, plan, ladder, rest_amt=REST, rests=2, potion_amt=POTION, potions=POTIONS):
    hp, r, p = hero.hp, rests, potions
    for key in plan:
        while hp < hero.hp * 0.8 and (r or p):
            if r and hp + rest_amt <= hero.hp:
                hp += rest_amt
                r -= 1
            elif p and hp + potion_amt <= hero.hp + 2:
                hp = min(hero.hp, hp + potion_amt)
                p -= 1
            else:
                break
        hp = fight(rng, hero, ladder[key], hp)
        if hp <= 0:
            return None, r + p
    return hp, r + p


def report_delve(label, hero, plan, ladder, n=40000):
    rng = random.Random(SEED)
    lost, left, spare = 0, [], []
    for _ in range(n):
        hp, s = delve(rng, hero, plan, ladder)
        if hp is None:
            lost += 1
        else:
            left.append(hp)
            spare.append(s)
    print(f"  {label:<44} lost {lost / n * 100:5.1f}%   "
          f"hp left {statistics.mean(left):4.1f}/{hero.hp}   items unspent {statistics.mean(spare):.2f}")


LADDER = {"mob2": [MOB] * 2, "mob3": [MOB] * 3, "brute": [BRUTE],
          "brute+mob2": [BRUTE] + [MOB] * 2}
CAREFUL = ["mob2", "brute", "mob3"]
GREEDY = ["mob2", "brute", "mob3", "brute", "brute+mob2"]


def main():
    print("1. Today's numbers — why the ticket's premise was wrong")
    print("   The ticket said five hit points is 'one bad roll from over'. It is not:")
    for hp in (1, 5, 9, 12):
        print(f"     fighter HP12 at {hp:>2} hp vs one goblin: survives "
              f"{survival(FIGHTER_12, [GOBLIN_OLD], hp) * 100:5.1f}%")
    _, fifty, inside = doubt_window(FIGHTER_12, [GOBLIN_OLD])
    print(f"     doubt window {min(inside)}-{max(inside)} hp ({len(inside)} wide), 50% at {fifty} hp"
          "  <- below the number the gate names")

    print()
    print("2. The action economy — why N weak enemies beats one strong one, at party size 1")
    for label, foes in [("1 brute", [BRUTE]), ("2 goblins (old stats)", [GOBLIN_OLD] * 2),
                        ("3 goblins (old stats)", [GOBLIN_OLD] * 3)]:
        print(f"     fighter HP12 vs {label:<22}: dropped from full "
              f"{(1 - survival(FIGHTER_12, foes, 12)) * 100:5.1f}%")

    print()
    print("3. The settled ladder, against fighter HP 20")
    for label, foes in [("2 mobs", LADDER["mob2"]), ("3 mobs", LADDER["mob3"]),
                        ("1 brute", LADDER["brute"]), ("brute + 2 mobs", LADDER["brute+mob2"])]:
        _, fifty, inside = doubt_window(FIGHTER, foes)
        win = f"{min(inside)}-{max(inside)}" if inside else "none"
        print(f"     vs {label:<15}: dropped from full {(1 - survival(FIGHTER, foes, 20)) * 100:5.1f}%"
              f"   50% at {fifty:>2} hp   doubt window {win:>5}"
              f"   survives at 5 hp {survival(FIGHTER, foes, 5) * 100:5.1f}%")

    print()
    print("4. The delve — a careful one leaves resources, a greedy one is a coin flip")
    report_delve("careful (3 fights, objective, out)", FIGHTER, CAREFUL, LADDER)
    report_delve("greedy  (5 fights, two rooms deeper)", FIGHTER, GREEDY, LADDER)

    print()
    print("5. Why the rest amount was chosen for legibility, not balance")
    for amt in (3, 4, 6, 8):
        rng = random.Random(SEED)
        lost = sum(1 for _ in range(20000)
                   if delve(rng, FIGHTER, CAREFUL, LADDER, rest_amt=amt)[0] is None)
        print(f"     rest restores {amt}: careful delve lost {lost / 20000 * 100:5.1f}%")


if __name__ == "__main__":
    main()
