#include <iostream>
#include <string>
#include <thread>
#include <chrono>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <iterator>
#include <limits>
#include <sstream>

#include "botcraft/AI/Tasks/AllTasks.hpp"
#include "botcraft/AI/TemplatedBehaviourClient.hpp"
#include "botcraft/Game/Entities/LocalPlayer.hpp"
#include "botcraft/Game/World/Blockstate.hpp"
#include "botcraft/Game/World/World.hpp"
#include "botcraft/Utilities/Logger.hpp"

using namespace Botcraft;
using namespace ProtocolCraft;

class AFKSleepClient : public TemplatedBehaviourClient<AFKSleepClient>
{
public:
    void TickSleep()
    {
        const auto now = std::chrono::steady_clock::now();
        if (now < dont_sleep_until || now < next_sleep_attempt)
        {
            return;
        }
        next_sleep_attempt = now + std::chrono::milliseconds(750);

        const std::shared_ptr<World> world = GetWorld();
        const std::shared_ptr<LocalPlayer> player = GetLocalPlayer();
        if (world == nullptr || player == nullptr)
        {
            return;
        }

#if PROTOCOL_VERSION >= 719
        if (world->GetCurrentDimension() != "minecraft:overworld")
        {
            return;
        }
#endif

        const Position bed = FindNearbyBed();
        if (bed == Position(INT32_MIN, INT32_MIN, INT32_MIN))
        {
            return;
        }

        const Status status = SyncAction(1500, InteractWithBlock, bed, PlayerDiggingFace::Up, true);
        if (status == Status::Success && now >= next_sleep_log)
        {
            next_sleep_log = now + std::chrono::seconds(30);
            LOG_INFO("AFK sleep: interacted with nearby bed at " << bed);
        }
    }

protected:
#if PROTOCOL_VERSION < 759 /* < 1.19 */
    virtual void Handle(ClientboundChatPacket& msg) override
    {
        ManagersClient::Handle(msg);
        ProcessChatText(msg.GetMessage().GetText());
    }
#else
    virtual void Handle(ClientboundPlayerChatPacket& msg) override
    {
        ManagersClient::Handle(msg);
#if PROTOCOL_VERSION == 759 /* 1.19 */
        ProcessChatText(msg.GetSignedContent().GetText());
#elif PROTOCOL_VERSION == 760 /* 1.19.1/2 */
        ProcessChatText(msg.GetMessage_().GetSignedBody().GetContent().GetPlain());
#else
        ProcessChatText(msg.GetBody().GetContent());
#endif
    }

    virtual void Handle(ClientboundSystemChatPacket& msg) override
    {
        ManagersClient::Handle(msg);
        ProcessChatText(msg.GetContent().GetText());
    }
#endif

private:
    std::chrono::steady_clock::time_point dont_sleep_until = std::chrono::steady_clock::time_point::min();
    std::chrono::steady_clock::time_point next_sleep_attempt = std::chrono::steady_clock::time_point::min();
    std::chrono::steady_clock::time_point next_sleep_log = std::chrono::steady_clock::time_point::min();

    void ProcessChatText(std::string text)
    {
        std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });

        if (text.find("dont sleep") == std::string::npos)
        {
            return;
        }

        dont_sleep_until = std::chrono::steady_clock::now() + std::chrono::minutes(10);
        LOG_INFO("AFK sleep: chat said dont sleep; pausing bed clicks for 10 minutes.");
    }

    Position FindNearbyBed()
    {
        const std::shared_ptr<World> world = GetWorld();
        const std::shared_ptr<LocalPlayer> player = GetLocalPlayer();
        if (world == nullptr || player == nullptr)
        {
            return Position(INT32_MIN, INT32_MIN, INT32_MIN);
        }

        const Vector3<double> player_pos = player->GetPosition();
        const Position base(
            static_cast<int>(std::floor(player_pos.x)),
            static_cast<int>(std::floor(player_pos.y)),
            static_cast<int>(std::floor(player_pos.z))
        );

        Position best(INT32_MIN, INT32_MIN, INT32_MIN);
        double best_dist = std::numeric_limits<double>::max();
        for (int dx = -2; dx <= 2; ++dx)
        {
            for (int dy = -1; dy <= 1; ++dy)
            {
                for (int dz = -2; dz <= 2; ++dz)
                {
                    const Position pos = base + Position(dx, dy, dz);
                    const Blockstate* block = world->GetBlock(pos);
                    if (block == nullptr || !block->IsBed())
                    {
                        continue;
                    }
                    const double dist = player_pos.SqrDist(Vector3<double>(0.5, 0.5, 0.5) + pos);
                    if (dist < best_dist)
                    {
                        best = pos;
                        best_dist = dist;
                    }
                }
            }
        }
        return best;
    }
};

void ShowHelp(const char* argv0)
{
    std::cout << "Usage: " << argv0 << " <options>\n"
        << "Options:\n"
        << "\t-h, --help\tShow this help message\n"
        << "\t--address\tAddress of the server you want to connect to, default: 127.0.0.1:25565\n"
        << "\t--login\t\tPlayer name in offline mode, empty for Microsoft account, default: BCAFK\n"
        << std::endl;
}

struct Args
{
    bool help = false;
    std::string address = "127.0.0.1:25565";
    std::string login = "BCAFK";

    int return_code = 0;
};

Args ParseCommandLine(int argc, char* argv[]);

int main(int argc, char* argv[])
{
    try
    {
        // Keep AFK loops quiet; warnings and fatal disconnect/crash diagnostics still reach the wrapper.
        Botcraft::Logger::GetInstance().SetLogLevel(Botcraft::LogLevel::Warning);
        Botcraft::Logger::GetInstance().SetFilename("");
        // Add a name to this thread for logging
        Botcraft::Logger::GetInstance().RegisterThread("main");

        Args args;
        if (argc == 1)
        {
            LOG_WARNING("No command arguments. Using default options.");
            ShowHelp(argv[0]);
        }
        else
        {
            args = ParseCommandLine(argc, argv);
            if (args.help)
            {
                ShowHelp(argv[0]);
                return 0;
            }
            if (args.return_code != 0)
            {
                return args.return_code;
            }
        }

        AFKSleepClient client;

        LOG_INFO("Starting connection process");
        client.Connect(args.address, args.login);
        client.StartBehaviour();

        while (true)
        {
            client.TickSleep();
            client.BehaviourStep();
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }

        client.Disconnect();

        return 0;
    }
    catch (std::exception& e)
    {
        LOG_FATAL("Exception: " << e.what());
        return 1;
    }
    catch (...)
    {
        LOG_FATAL("Unknown exception");
        return 2;
    }
}

Args ParseCommandLine(int argc, char* argv[])
{
    Args args;
    for (int i = 1; i < argc; ++i)
    {
        std::string arg = argv[i];
        if (arg == "-h" || arg == "--help")
        {
            ShowHelp(argv[0]);
            args.help = true;
            return args;
        }
        else if (arg == "--address")
        {
            if (i + 1 < argc)
            {
                args.address = argv[++i];
            }
            else
            {
                LOG_FATAL("--address requires an argument");
                args.return_code = 1;
                return args;
            }
        }
        else if (arg == "--login")
        {
            if (i + 1 < argc)
            {
                args.login = argv[++i];
            }
            else
            {
                LOG_FATAL("--login requires an argument");
                args.return_code = 1;
                return args;
            }
        }
    }
    return args;
}
