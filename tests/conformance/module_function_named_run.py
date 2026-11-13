# A module-level function named `run` must not clash with the generated entry method of the module class.
def run(cmd, args):
    return "%s:%s" % (cmd, args)


if __name__ == "__main__":
    def run(cmd, args):
        return "main-" + cmd + str(args)
    print(run("a", (1,)))
print(run("b", 2))
