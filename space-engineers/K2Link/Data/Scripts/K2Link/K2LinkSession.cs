using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using Sandbox.ModAPI;
using VRage.Game;
using VRage.Game.Components;
using VRage.Game.ModAPI;
using VRage.Game.ModAPI.Interfaces;
using VRage.ModAPI;
using VRageMath;
using IngameBattery = Sandbox.ModAPI.Ingame.IMyBatteryBlock;
using IngameCargo = Sandbox.ModAPI.Ingame.IMyCargoContainer;
using IngameConnector = Sandbox.ModAPI.Ingame.IMyShipConnector;
using IngameGasTank = Sandbox.ModAPI.Ingame.IMyGasTank;
using IngameJumpDrive = Sandbox.ModAPI.Ingame.IMyJumpDrive;
using IngameTerminalBlock = Sandbox.ModAPI.Ingame.IMyTerminalBlock;

namespace K2Link
{
    /// <summary>
    /// K2 Link: writes what the local player is doing to ONE small JSON file,
    /// %AppData%\SpaceEngineers\Storage\&lt;mod&gt;_K2Link\state.json, a few times a second. K2 reads
    /// it to light the keys of its Space Engineers game profile on a Mountain DisplayPad.
    ///
    /// Read-only: nothing here changes the game. The pad presses the player's own key binds; this
    /// only says what to light up. Mods cannot open sockets, so a file in the mod's local storage
    /// is the whole transport. Nothing runs on a dedicated server — only on the player's client.
    ///
    /// Written in C# 6 and against whitelisted ModAPI only: the game compiles mod scripts itself.
    /// </summary>
    [MySessionComponentDescriptor(MyUpdateOrder.AfterSimulation)]
    public class K2LinkSession : MySessionComponentBase
    {
        /// <summary>This mod's version, published in the state so K2 can tell when a newer one is out.</summary>
        private const string Version = "1.0.0";
        private const string FileName = "state.json";
        private const int StateEveryFrames = 15;   // 4 Hz at 60 UPS
        private const int SlowEveryFrames = 60;    // block scans and the planet lookup: 1 Hz

        private static readonly MyDefinitionId OxygenId = MyDefinitionId.Parse("MyObjectBuilder_GasProperties/Oxygen");
        private static readonly MyDefinitionId HydrogenId = MyDefinitionId.Parse("MyObjectBuilder_GasProperties/Hydrogen");

        private readonly Dictionary<string, object> _slow = new Dictionary<string, object>();
        private readonly List<IngameTerminalBlock> _blocks = new List<IngameTerminalBlock>();
        private readonly List<IMyVoxelBase> _voxels = new List<IMyVoxelBase>();
        private string _planet;
        private int _frame;
        private bool _disabled;
        private string _lastWritten;

        public override void UpdateAfterSimulation()
        {
            if (_disabled || ++_frame % StateEveryFrames != 0) return;
            if (MyAPIGateway.Utilities == null || MyAPIGateway.Utilities.IsDedicated) return;

            string json;
            try { json = BuildState(_frame % SlowEveryFrames == 0); }
            catch (Exception ex) { json = "{\"k2\":1,\"inGame\":false,\"error\":" + Str(ex.Message ?? "") + "}"; }
            Write(json);
        }

        protected override void UnloadData()
        {
            // Leaving the world: say so, or K2 would keep showing the last ship until the file goes stale.
            if (MyAPIGateway.Utilities != null && !MyAPIGateway.Utilities.IsDedicated)
                Write("{\"k2\":1,\"inGame\":false}");
        }

        private void Write(string json)
        {
            if (json == _lastWritten) return;
            try
            {
                using (var w = MyAPIGateway.Utilities.WriteFileInLocalStorage(FileName, typeof(K2LinkSession)))
                    w.Write(json);
                _lastWritten = json;
            }
            catch (Exception ex)
            {
                // A locked or unwritable storage folder must not spam the log every frame.
                _disabled = true;
                MyLog("K2 Link: cannot write " + FileName + ": " + ex.Message);
            }
        }

        private static void MyLog(string line)
        {
            try { VRage.Utils.MyLog.Default.WriteLineAndConsole(line); } catch { }
        }

        // ------------------------------------------------------------------ state

        private string BuildState(bool slow)
        {
            var session = MyAPIGateway.Session;
            var player = session == null ? null : session.Player;
            var character = player == null ? null : player.Character;
            if (character == null) return "{\"k2\":1,\"inGame\":false}";

            var f = new Dictionary<string, object>();
            f["k2"] = 1;
            f["modVersion"] = Version;
            f["inGame"] = true;
            f["dead"] = character.IsDead;
            // A changing stamp lets K2 tell a live file from one a crashed game left behind.
            f["t"] = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond;

            // Suit: always the character's, wherever it sits.
            f["suitEnergy"] = character.SuitEnergyLevel;
            f["suitOxygen"] = character.GetSuitGasFillLevel(OxygenId);
            f["suitHydrogen"] = character.GetSuitGasFillLevel(HydrogenId);
            f["helmet"] = character.EnabledHelmet;
            f["jetpack"] = character.EnabledThrusts;
            var destroyable = character as IMyDestroyableObject;
            if (destroyable != null) f["health"] = destroyable.Integrity / 100f;

            // Flight toggles follow what the player CONTROLS: the suit on foot, the ship in a seat.
            var controlled = session.ControlledObject;
            var sc = controlled as IMyShipController;
            Vector3D pos = character.GetPosition();
            f["inCockpit"] = sc != null;
            if (controlled != null)
            {
                f["dampeners"] = controlled.EnabledDamping;
                f["relativeDampeners"] = controlled.RelativeDampeningTarget != null;
                f["lights"] = controlled.EnabledLights;
            }

            Vector3 gravity;
            if (sc != null)
            {
                pos = sc.GetPosition();
                f["parked"] = controlled.EnabledLeadingGears;
                f["shipPower"] = controlled.EnabledReactors;
                f["handbrake"] = sc.HandBrake;
                f["speed"] = sc.GetShipSpeed();
                if (sc.CubeGrid != null) f["shipName"] = sc.CubeGrid.CustomName;
                f["shipMass"] = sc.CalculateShipMass().PhysicalMass / 1000.0;
                double elevation;
                if (sc.TryGetPlanetElevation(Sandbox.ModAPI.Ingame.MyPlanetElevation.Surface, out elevation))
                    f["altitude"] = elevation;
                gravity = (Vector3)sc.GetNaturalGravity();

                if (slow) ScanGrid(sc.CubeGrid);
                foreach (var kv in _slow) f[kv.Key] = kv.Value;
            }
            else
            {
                _slow.Clear();
                if (character.Physics != null) f["speed"] = character.Physics.LinearVelocity.Length();
                gravity = MyAPIGateway.GravityProviderSystem != null
                    ? MyAPIGateway.GravityProviderSystem.CalculateNaturalGravityInPoint(pos)
                    : Vector3.Zero;
            }

            float g = gravity.Length() / 9.81f;
            f["gravity"] = g;
            if (g > 0.001f)
            {
                if (slow || _planet == null) _planet = NearestPlanet(pos);
                if (_planet != null) f["planet"] = _planet;
            }
            else _planet = null;

            return Json(f);
        }

        /// <summary>The planet whose gravity the player is in, by its storage name without the
        /// numeric suffix the game appends ("EarthLike-1234567d120000" -> "EarthLike").</summary>
        private string NearestPlanet(Vector3D pos)
        {
            if (MyAPIGateway.Session.VoxelMaps == null || MyAPIGateway.GravityProviderSystem == null) return null;
            _voxels.Clear();
            MyAPIGateway.Session.VoxelMaps.GetInstances(_voxels,
                v => MyAPIGateway.GravityProviderSystem.DoesEntityHaveGravityProvider(v));
            IMyVoxelBase best = null;
            double bestDist = double.MaxValue;
            foreach (var v in _voxels)
            {
                double d = Vector3D.DistanceSquared(v.WorldVolume.Center, pos);
                if (d < bestDist) { bestDist = d; best = v; }
            }
            if (best == null || string.IsNullOrEmpty(best.StorageName)) return null;
            string name = best.StorageName;
            int dash = name.IndexOf('-');
            return dash > 0 ? name.Substring(0, dash) : name;
        }

        /// <summary>Everything the grid's terminal system reaches (the ship plus whatever it is
        /// mechanically attached to), the way the game's own ship info panel adds it up.</summary>
        private void ScanGrid(IMyCubeGrid grid)
        {
            _slow.Clear();
            if (grid == null || MyAPIGateway.TerminalActionsHelper == null) return;
            var gts = MyAPIGateway.TerminalActionsHelper.GetTerminalSystemForGrid(grid);
            if (gts == null) return;

            _blocks.Clear();
            gts.GetBlocks(_blocks);

            double bNow = 0, bMax = 0, h2 = 0, h2Cap = 0, o2 = 0, o2Cap = 0, jNow = 0, jMax = 0;
            double cargo = 0, cargoMax = 0;
            bool anyConnector = false, locked = false, anyJump = false;
            foreach (var b in _blocks)
            {
                if (!b.IsFunctional) continue;

                var bat = b as IngameBattery;
                if (bat != null) { bNow += bat.CurrentStoredPower; bMax += bat.MaxStoredPower; continue; }

                var tank = b as IngameGasTank;
                if (tank != null)
                {
                    bool hydrogen = b.BlockDefinition.SubtypeId.IndexOf("Hydrogen", StringComparison.OrdinalIgnoreCase) >= 0;
                    if (hydrogen) { h2 += tank.FilledRatio * tank.Capacity; h2Cap += tank.Capacity; }
                    else { o2 += tank.FilledRatio * tank.Capacity; o2Cap += tank.Capacity; }
                    continue;
                }

                var box = b as IngameCargo;
                if (box != null && box.HasInventory)
                {
                    var inv = box.GetInventory(0);
                    cargo += (double)inv.CurrentVolume; cargoMax += (double)inv.MaxVolume;
                    continue;
                }

                var con = b as IngameConnector;
                if (con != null)
                {
                    anyConnector = true;
                    if (con.Status == Sandbox.ModAPI.Ingame.MyShipConnectorStatus.Connected) locked = true;
                    continue;
                }

                var jd = b as IngameJumpDrive;
                if (jd != null) { anyJump = true; jNow += jd.CurrentStoredPower; jMax += jd.MaxStoredPower; }
            }
            if (bMax > 0) _slow["shipBattery"] = bNow / bMax;
            if (h2Cap > 0) _slow["shipHydrogen"] = h2 / h2Cap;
            if (o2Cap > 0) _slow["shipOxygen"] = o2 / o2Cap;
            if (cargoMax > 0) _slow["shipCargo"] = cargo / cargoMax;
            if (anyConnector) _slow["connectorLocked"] = locked;
            if (anyJump && jMax > 0) _slow["jumpCharge"] = jNow / jMax;
        }

        // ------------------------------------------------------------------ JSON

        private static string Json(Dictionary<string, object> f)
        {
            var sb = new StringBuilder(512);
            sb.Append('{');
            bool first = true;
            foreach (var kv in f)
            {
                if (kv.Value == null) continue;
                if (!first) sb.Append(',');
                first = false;
                sb.Append(Str(kv.Key)).Append(':');
                object v = kv.Value;
                if (v is bool) sb.Append((bool)v ? "true" : "false");
                else if (v is string) sb.Append(Str((string)v));
                else if (v is float) sb.Append(Num((float)v));
                else if (v is double) sb.Append(Num((double)v));
                else sb.Append(Convert.ToString(v, CultureInfo.InvariantCulture));
            }
            return sb.Append('}').ToString();
        }

        private static string Num(double v)
        {
            return double.IsNaN(v) || double.IsInfinity(v) ? "null" : v.ToString("0.####", CultureInfo.InvariantCulture);
        }

        private static string Str(string s)
        {
            var sb = new StringBuilder(s.Length + 2).Append('"');
            foreach (char c in s)
            {
                if (c == '"' || c == '\\') sb.Append('\\').Append(c);
                else if (c < 0x20) sb.Append(' ');
                else sb.Append(c);
            }
            return sb.Append('"').ToString();
        }
    }
}
