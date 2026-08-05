 # SleepyAddon

  A Meteor Client **cleaning & utility** addon.

  ## Modules

  - **AntiCheat**
    Manages internal addon settings, including AirPlace hand mode and Grim direction for mining/placement packets.
    *Note:* It effectively behaves like an always-on module, so enabling/disabling it usually doesn’t matter.

  - **AirPlace**
    Place the block in your hand anywhere (including mid-air). Range is configurable.

  - **Printer**
    A Litematica-style and Baritone selection printing tool integrated into SleepyAddon. It can:
    - Automatically place schematic blocks in survival
    - Fill Baritone selections
    - Automatically mine **wrong** and **extra** blocks
    - Use SilentMine for blocked paths and cleanup

  - **MossPlacer**
    Places moss on top of valid solid blocks, with optional side placement, depth control, and fading placement renders.

  - **GlowBerryPlacer**
    Places glow berries under valid overhead blocks and renders recent placements.

  - **MossSpreader**
    Bonemeals moss edges, clears blocking snow when enabled, crafts bone meal from bone blocks, and cleans junk inventory slots.

  - **SilentMine**
    A packet/silent mining module for **2b2t**, designed to make mining smoother, with queued mining, rebreak support, instant-break handling, and Printer mining integration.

  - **WorldEater**
    A large-area cleaning module for removing terrain and blocks. Designed for bigger cleanup jobs where normal manual mining or small Printer cleanup is too slow.


  ## Dependencies

  - [Meteor Client](https://meteorclient.com/archive)
  - [Litematica](https://modrinth.com/mod/litematica/versions)
  - [MaLiLib](https://modrinth.com/mod/malilib/versions)

  ## Credits

  - **SleepyFemboy** — I made this
  - **ChatGPT (OpenAI)** — GPT my beloved also yes it wrote most this README 💫
  - The **SilentMine** module is based on / adapted from Gonbleware:
    https://github.com/Shays-Forks/meteor-client-gonbleware-fork
