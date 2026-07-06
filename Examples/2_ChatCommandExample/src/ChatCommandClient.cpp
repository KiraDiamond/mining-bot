#include <sstream>
#include <fstream>
#include <iostream>
#include <iterator>
#include <chrono>
#include <limits>
#include <algorithm>

#include "botcraft/Game/World/World.hpp"
#include "botcraft/Game/Entities/EntityManager.hpp"
#include "botcraft/Game/Entities/LocalPlayer.hpp"
#include "botcraft/Network/NetworkManager.hpp"

#include "botcraft/AI/BehaviourTree.hpp"
#include "botcraft/AI/Tasks/AllTasks.hpp"

#include "ChatCommandClient.hpp"

using namespace Botcraft;
using namespace ProtocolCraft;

ChatCommandClient::ChatCommandClient() : TemplatedBehaviourClient<ChatCommandClient>()
{
    std::cout << "Known commands:\n";
    std::cout << "    Pathfinding to position:\n";
    std::cout << "        name goto x y z (speed_multiplier=1.0)\n";
    std::cout << "    Stop what you're doing:\n";
    std::cout << "        name stop\n";
    std::cout << "    Check perimeter for spawnable blocks and save spawnable positions to file:\n";
    std::cout << "        name check_perimeter [x y z (default = player position)] radius (default = 128) [check_lighting (default = true)]\n";
    std::cout << "    Disconnect:\n";
    std::cout << "        name die\n";
    std::cout << "    Place a block:\n";
    std::cout << "        name place_block minecraft:item x y z\n";
    std::cout << "    Break a block:\n";
    std::cout << "        name dig x y z\n";
    std::cout << "    Mine matching loaded blocks near the bot:\n";
    std::cout << "        name mine_near minecraft:block count radius\n";
    std::cout << "    Clear loaded blocks inside a cuboid:\n";
    std::cout << "        name clear_cuboid x1 y1 z1 x2 y2 z2 max_blocks\n";
    std::cout << "    Interact (right click) a block:\n";
    std::cout << "        name interact x y z\n";
}

ChatCommandClient::~ChatCommandClient()
{

}

void ChatCommandClient::ProcessExternalCommand(const std::vector<std::string>& command)
{
    if (command.empty() || network_manager == nullptr)
    {
        return;
    }

    std::vector<std::string> prefixed;
    prefixed.reserve(command.size() + 1);
    prefixed.push_back(network_manager->GetMyName());
    prefixed.insert(prefixed.end(), command.begin(), command.end());
    ProcessChatMsg(prefixed);
}

#if PROTOCOL_VERSION < 759 /* < 1.19 */
void ChatCommandClient::Handle(ClientboundChatPacket& msg)
{
    ManagersClient::Handle(msg);

    // Split the message
    std::istringstream ss{ msg.GetMessage().GetText() };
    const std::vector<std::string> splitted({ std::istream_iterator<std::string>{ss}, std::istream_iterator<std::string>{} });

    // Process it
    ProcessChatMsg(splitted);
}
#else
void ChatCommandClient::Handle(ClientboundPlayerChatPacket& msg)
{
    ManagersClient::Handle(msg);

    // Split the message
#if PROTOCOL_VERSION == 759 /* 1.19 */
    std::istringstream ss{ msg.GetSignedContent().GetText() };
#elif PROTOCOL_VERSION == 760 /* 1.19.1/2 */
    std::istringstream ss{ msg.GetMessage_().GetSignedBody().GetContent().GetPlain() };
#else
    std::istringstream ss{ msg.GetBody().GetContent() };
#endif
    const std::vector<std::string> splitted({ std::istream_iterator<std::string>{ss}, std::istream_iterator<std::string>{} });

    // Process it
    ProcessChatMsg(splitted);
}

void ChatCommandClient::Handle(ClientboundSystemChatPacket& msg)
{
    ManagersClient::Handle(msg);

    // Split the message
    std::istringstream ss{ msg.GetContent().GetText() };
    const std::vector<std::string> splitted({ std::istream_iterator<std::string>{ss}, std::istream_iterator<std::string>{} });

    // Process it
    ProcessChatMsg(splitted);
}
#endif

void ChatCommandClient::ProcessChatMsg(const std::vector<std::string>& splitted_msg)
{
    if (splitted_msg.size() < 2 || splitted_msg[0] != network_manager->GetMyName())
    {
        return;
    }

    if (splitted_msg[1] == "goto")
    {
        if (splitted_msg.size() < 5)
        {
            SendChatMessage("Usage: [BotName] [goto] [x] [y] [z] [speed_multiplier]");
            return;
        }
        Position target_position;
        float speed_multiplier = 1.0f;
        try
        {
            target_position = Position(std::stoi(splitted_msg[2]), std::stoi(splitted_msg[3]), std::stoi(splitted_msg[4]));
            if (splitted_msg.size() > 5)
            {
                speed_multiplier = std::stof(splitted_msg[5]);
            }
        }
        catch (const std::invalid_argument&)
        {
            return;
        }
        catch (const std::out_of_range&)
        {
            return;
        }

        auto tree = Builder<ChatCommandClient>("goto tree")
            .sequence()
                // Perform the pathfinding in a Selector,
                // so it exits as soon as one leaf
                // returns success
                .selector()
                    // The next three lines do exactly the same,
                    // they're only here to show the different
                    // possibilities to create a leaf. Note that
                    // only the lambda solution can use default
                    // parameters values
                    .leaf("go to lambda", [=](ChatCommandClient& c) { return GoTo(c, target_position, 0, 0, 0, true, false, speed_multiplier); })
                    .leaf("go to function", GoTo, target_position, 0, 0, 0, true, false, speed_multiplier)
                    .leaf("go to std::bind", std::bind(GoTo, std::placeholders::_1, target_position, 0, 0, 0, true, false, speed_multiplier))
                    // If goto fails, say something in chat
                    .leaf(Say, "Pathfinding failed :(")
                .end()
                // Switch back to empty behaviour
                .leaf([](ChatCommandClient& c) { c.SetBehaviourTree(nullptr); return Status::Success; })
            .end();

        SetBehaviourTree(tree);
    }
    else if (splitted_msg[1] == "stop")
    {
        // Stop any running behaviour
        SetBehaviourTree(nullptr);
    }
    else if (splitted_msg[1] == "where")
    {
        const std::shared_ptr<LocalPlayer> player = entity_manager->GetLocalPlayer();
        if (player == nullptr)
        {
            std::cout << "WHERE unavailable: no local player\n";
            return;
        }
        const Vector3<double> pos = player->GetPosition();
        std::cout << "WHERE " << network_manager->GetMyName()
            << " pos=" << pos.x << "," << pos.y << "," << pos.z
            << " on_ground=" << player->GetOnGround()
            << " climbing=" << player->IsClimbing()
            << " fluid=" << player->IsInFluid()
            << " invulnerable=" << player->GetInvulnerable()
            << " dirty_inputs=" << player->GetDirtyInputs()
            << "\n";
    }
    else if (splitted_msg[1] == "walk")
    {
        if (splitted_msg.size() < 5)
        {
            std::cout << "Usage: [BotName] [walk] [dx] [dz] [seconds]\n";
            return;
        }

        double dx = 0.0;
        double dz = 0.0;
        double seconds = 0.0;
        try
        {
            dx = std::stod(splitted_msg[2]);
            dz = std::stod(splitted_msg[3]);
            seconds = std::stod(splitted_msg[4]);
        }
        catch (const std::invalid_argument&)
        {
            return;
        }
        catch (const std::out_of_range&)
        {
            return;
        }
        if (seconds <= 0.0)
        {
            return;
        }

        auto tree = Builder<ChatCommandClient>("walk tree")
            .sequence()
                .leaf([=](ChatCommandClient& c) {
                    const std::shared_ptr<LocalPlayer> player = c.GetLocalPlayer();
                    if (player == nullptr)
                    {
                        return Status::Failure;
                    }

                    const std::chrono::steady_clock::time_point end = std::chrono::steady_clock::now() + std::chrono::milliseconds(static_cast<int>(seconds * 1000.0));
                    while (std::chrono::steady_clock::now() < end)
                    {
                        const Vector3<double> pos = player->GetPosition();
                        player->LookAt(Vector3<double>(pos.x + dx, pos.y, pos.z + dz), false);
                        player->SetInputsForward(1.0f);
                        player->SetInputsSprint(true);
                        c.Yield();
                    }
                    player->SetInputsForward(0.0f);
                    player->SetInputsSprint(false);
                    return Status::Success;
                })
                .leaf([](ChatCommandClient& c) {
                    const std::shared_ptr<LocalPlayer> player = c.GetLocalPlayer();
                    if (player != nullptr)
                    {
                        player->SetInputsForward(0.0f);
                        player->SetInputsSprint(false);
                    }
                    c.SetBehaviourTree(nullptr);
                    return Status::Success;
                })
            .end();

        SetBehaviourTree(tree);
    }
    else if (splitted_msg[1] == "check_perimeter")
    {
        float radius = 128.0f;
        Position pos = Position(
            static_cast<int>(std::floor(entity_manager->GetLocalPlayer()->GetPosition().x)),
            static_cast<int>(std::floor(entity_manager->GetLocalPlayer()->GetPosition().y)),
            static_cast<int>(std::floor(entity_manager->GetLocalPlayer()->GetPosition().z))
        );
        bool check_lighting = true;

        if (splitted_msg.size() == 3)
        {
            radius = std::stof(splitted_msg[2]);
        }
        else if (splitted_msg.size() == 4)
        {
            radius = std::stof(splitted_msg[2]);
            check_lighting = std::stoi(splitted_msg[3]);
        }
        else if (splitted_msg.size() == 6)
        {
            pos = Position(std::stoi(splitted_msg[2]), std::stoi(splitted_msg[3]), std::stoi(splitted_msg[4]));
            radius = std::stof(splitted_msg[5]);
        }
        else if (splitted_msg.size() == 7)
        {
            pos = Position(std::stoi(splitted_msg[2]), std::stoi(splitted_msg[3]), std::stoi(splitted_msg[4]));
            radius = std::stof(splitted_msg[5]);
            check_lighting = std::stoi(splitted_msg[6]);
        }
        CheckPerimeter(pos, radius, check_lighting);
    }
    else if (splitted_msg[1] == "die")
    {
        should_be_closed = true;
    }
    else if (splitted_msg[1] == "place_block")
    {
        if (splitted_msg.size() < 6)
        {
            SendChatMessage("Usage: [BotName] [place_block] [item] [x] [y] [z]");
            return;
        }
        const std::string& item = splitted_msg[2];
        Position pos;
        try
        {
            pos = Position(std::stoi(splitted_msg[3]), std::stoi(splitted_msg[4]), std::stoi(splitted_msg[5]));
        }
        catch (const std::invalid_argument&)
        {
            return;
        }
        catch (const std::out_of_range&)
        {
            return;
        }
        LOG_INFO("Asked to place a block at " << pos << " (" << item << ")");

        auto tree = Builder<ChatCommandClient>("place block")
            // shortcut for composite<Sequence<ChatCommandClient>>()
            .sequence()
                .succeeder().leaf(PlaceBlock, item, pos, PlayerDiggingFace::Up, true, true, true)
                // Switch back to empty behaviour
                .leaf([](ChatCommandClient& c) { c.SetBehaviourTree(nullptr); return Status::Success; })
            .end();

        SetBehaviourTree(tree);
    }
    else if (splitted_msg[1] == "dig")
    {
        if (splitted_msg.size() < 5)
        {
            SendChatMessage("Usage: [BotName] [dig] [x] [y] [z]");
            return;
        }

        Position pos;
        try
        {
            pos = Position(std::stoi(splitted_msg[2]), std::stoi(splitted_msg[3]), std::stoi(splitted_msg[4]));
        }
        catch (const std::invalid_argument&)
        {
            return;
        }
        catch (const std::out_of_range&)
        {
            return;
        }

        auto tree = Builder<ChatCommandClient>("dig")
            // shortcut for composite<Sequence<ChatCommandClient>>()
            .sequence()
                .succeeder().leaf("diggy diggy hole", Dig, pos, true, PlayerDiggingFace::Up, true)
                // Switch back to empty behaviour
                .leaf([](ChatCommandClient& c) { c.SetBehaviourTree(nullptr); return Status::Success; })
            .end();

        SetBehaviourTree(tree);
    }
    else if (splitted_msg[1] == "mine_near")
    {
        if (splitted_msg.size() < 5)
        {
            std::cout << "Usage: [BotName] [mine_near] [minecraft:block] [count] [radius]\n";
            return;
        }

        std::string block_name = splitted_msg[2];
        if (block_name.find(':') == std::string::npos)
        {
            block_name = "minecraft:" + block_name;
        }

        int count = 0;
        int radius = 0;
        try
        {
            count = std::stoi(splitted_msg[3]);
            radius = std::stoi(splitted_msg[4]);
        }
        catch (const std::invalid_argument&)
        {
            return;
        }
        catch (const std::out_of_range&)
        {
            return;
        }

        if (count < 1 || radius < 1)
        {
            return;
        }
        count = std::min(count, 64);
        radius = std::min(radius, 8);

        auto tree = Builder<ChatCommandClient>("mine near")
            .sequence()
                .leaf([=](ChatCommandClient& c) {
                    int mined = 0;
                    std::vector<Position> skipped;
                    while (mined < count)
                    {
                        const std::shared_ptr<LocalPlayer> player = c.GetLocalPlayer();
                        const std::shared_ptr<World> current_world = c.GetWorld();
                        if (player == nullptr || current_world == nullptr)
                        {
                            return Status::Failure;
                        }

                        const Vector3<double> current = player->GetPosition();
                        const Position center(
                            static_cast<int>(std::floor(current.x)),
                            static_cast<int>(std::floor(current.y)),
                            static_cast<int>(std::floor(current.z))
                        );

                        bool found = false;
                        Position best;
                        int best_dist = std::numeric_limits<int>::max();
                        const int min_y = std::max(current_world->GetMinY(), center.y - 1);
                        const int max_y = std::min(current_world->GetMinY() + current_world->GetHeight() - 1, center.y + 3);

                        for (int y = min_y; y <= max_y; ++y)
                        {
                            for (int x = center.x - radius; x <= center.x + radius; ++x)
                            {
                                for (int z = center.z - radius; z <= center.z + radius; ++z)
                                {
                                    const Position pos(x, y, z);
                                    if (!current_world->IsLoaded(pos))
                                    {
                                        continue;
                                    }
                                    if (std::find(skipped.begin(), skipped.end(), pos) != skipped.end())
                                    {
                                        continue;
                                    }
                                    const Blockstate* block = current_world->GetBlock(pos);
                                    if (block == nullptr || block->GetName() != block_name)
                                    {
                                        continue;
                                    }
                                    const int dx = center.x - x;
                                    const int dy = center.y - y;
                                    const int dz = center.z - z;
                                    const int dist = dx * dx + dy * dy + dz * dz;
                                    if (dist < best_dist)
                                    {
                                        best_dist = dist;
                                        best = pos;
                                        found = true;
                                    }
                                }
                            }
                        }

                        if (!found)
                        {
                            std::cout << "MINE_NEAR stopped: no loaded " << block_name
                                << " found within safe reach " << radius
                                << " after mining " << mined << "\n";
                            return Status::Success;
                        }

                        std::cout << "MINE_NEAR digging " << block_name << " at " << best
                            << " (" << (mined + 1) << "/" << count << ")\n";
                        if (Dig(c, best, true, PlayerDiggingFace::Up, false) == Status::Failure)
                        {
                            std::cout << "MINE_NEAR skipped unreachable/failed " << best << "\n";
                            skipped.push_back(best);
                            if (skipped.size() > 128)
                            {
                                std::cout << "MINE_NEAR stopped: too many failed blocks after mining " << mined << "\n";
                                return Status::Success;
                            }
                            continue;
                        }
                        mined += 1;
                    }

                    std::cout << "MINE_NEAR complete: mined " << mined << " " << block_name << "\n";
                    return Status::Success;
                })
                .leaf([](ChatCommandClient& c) { c.SetBehaviourTree(nullptr); return Status::Success; })
            .end();

        SetBehaviourTree(tree);
    }
    else if (splitted_msg[1] == "clear_cuboid")
    {
        if (splitted_msg.size() < 9)
        {
            std::cout << "Usage: [BotName] [clear_cuboid] [x1] [y1] [z1] [x2] [y2] [z2] [max_blocks]\n";
            return;
        }

        Position a;
        Position b;
        int max_blocks = 0;
        try
        {
            a = Position(std::stoi(splitted_msg[2]), std::stoi(splitted_msg[3]), std::stoi(splitted_msg[4]));
            b = Position(std::stoi(splitted_msg[5]), std::stoi(splitted_msg[6]), std::stoi(splitted_msg[7]));
            max_blocks = std::stoi(splitted_msg[8]);
        }
        catch (const std::invalid_argument&)
        {
            return;
        }
        catch (const std::out_of_range&)
        {
            return;
        }

        if (max_blocks < 1)
        {
            return;
        }
        max_blocks = std::min(max_blocks, 2048);

        const Position min_pos(
            std::min(a.x, b.x),
            std::min(a.y, b.y),
            std::min(a.z, b.z)
        );
        const Position max_pos(
            std::max(a.x, b.x),
            std::max(a.y, b.y),
            std::max(a.z, b.z)
        );
        std::vector<Position> waypoints;
        bool reverse_row = false;
        for (int x = min_pos.x; x <= max_pos.x; x += 16)
        {
            std::vector<Position> row;
            for (int z = min_pos.z; z <= max_pos.z; z += 16)
            {
                row.push_back(Position(x, min_pos.y + 1, z));
            }
            if (row.empty() || row.back().z != max_pos.z)
            {
                row.push_back(Position(x, min_pos.y + 1, max_pos.z));
            }
            if (reverse_row)
            {
                std::reverse(row.begin(), row.end());
            }
            waypoints.insert(waypoints.end(), row.begin(), row.end());
            reverse_row = !reverse_row;
        }
        if (waypoints.empty() || waypoints.back().x != max_pos.x)
        {
            for (int z = min_pos.z; z <= max_pos.z; z += 16)
            {
                waypoints.push_back(Position(max_pos.x, min_pos.y + 1, z));
            }
            if (waypoints.empty() || waypoints.back().z != max_pos.z)
            {
                waypoints.push_back(Position(max_pos.x, min_pos.y + 1, max_pos.z));
            }
        }

        auto tree = Builder<ChatCommandClient>("clear cuboid")
            .sequence()
                .leaf([=](ChatCommandClient& c) {
                    int mined = 0;
                    std::vector<Position> skipped;
                    std::vector<Position> remaining_waypoints = waypoints;

                    while (mined < max_blocks)
                    {
                        const std::shared_ptr<LocalPlayer> player = c.GetLocalPlayer();
                        const std::shared_ptr<World> current_world = c.GetWorld();
                        if (player == nullptr || current_world == nullptr)
                        {
                            return Status::Failure;
                        }

                        const Vector3<double> current = player->GetPosition();
                        const Position center(
                            static_cast<int>(std::floor(current.x)),
                            static_cast<int>(std::floor(current.y)),
                            static_cast<int>(std::floor(current.z))
                        );

                        bool found_reachable = false;
                        bool found_any = false;
                        Position best_reachable;
                        Position best_any;
                        int best_reachable_dist = std::numeric_limits<int>::max();
                        int best_any_dist = std::numeric_limits<int>::max();
                        const int scan_radius = 8;
                        const int scan_min_x = std::max(min_pos.x, center.x - scan_radius);
                        const int scan_max_x = std::min(max_pos.x, center.x + scan_radius);
                        const int scan_min_z = std::max(min_pos.z, center.z - scan_radius);
                        const int scan_max_z = std::min(max_pos.z, center.z + scan_radius);

                        for (int y = max_pos.y; y >= min_pos.y; --y)
                        {
                            for (int x = scan_min_x; x <= scan_max_x; ++x)
                            {
                                for (int z = scan_min_z; z <= scan_max_z; ++z)
                                {
                                    const Position pos(x, y, z);
                                    if (!current_world->IsLoaded(pos) ||
                                        std::find(skipped.begin(), skipped.end(), pos) != skipped.end())
                                    {
                                        continue;
                                    }
                                    const Blockstate* block = current_world->GetBlock(pos);
                                    if (block == nullptr ||
                                        block->IsAir() ||
                                        block->IsFluid() ||
                                        block->GetName() == "minecraft:bedrock" ||
                                        block->GetName() == "minecraft:barrier")
                                    {
                                        continue;
                                    }

                                    found_any = true;
                                    const int dx = center.x - x;
                                    const int dy = center.y - y;
                                    const int dz = center.z - z;
                                    const int dist = dx * dx + dy * dy + dz * dz;
                                    if (dist < best_any_dist)
                                    {
                                        best_any_dist = dist;
                                        best_any = pos;
                                    }
                                    if (std::abs(dx) <= 5 && std::abs(dy) <= 4 && std::abs(dz) <= 5 && dist < best_reachable_dist)
                                    {
                                        best_reachable_dist = dist;
                                        best_reachable = pos;
                                        found_reachable = true;
                                    }
                                }
                            }
                        }

                        if (!found_any)
                        {
                            if (remaining_waypoints.empty())
                            {
                                std::cout << "CLEAR_CUBOID complete/no local waypoints left after mining " << mined << "\n";
                                return Status::Success;
                            }

                            auto nearest = remaining_waypoints.begin();
                            int nearest_dist = std::numeric_limits<int>::max();
                            for (auto it = remaining_waypoints.begin(); it != remaining_waypoints.end(); ++it)
                            {
                                const int dx = center.x - it->x;
                                const int dz = center.z - it->z;
                                const int dist = dx * dx + dz * dz;
                                if (dist < nearest_dist)
                                {
                                    nearest_dist = dist;
                                    nearest = it;
                                }
                            }

                            const Position waypoint(nearest->x, center.y, nearest->z);
                            remaining_waypoints.erase(nearest);
                            if (std::abs(center.x - waypoint.x) <= scan_radius &&
                                std::abs(center.z - waypoint.z) <= scan_radius)
                            {
                                continue;
                            }

                            std::cout << "CLEAR_CUBOID sweeping to " << waypoint
                                << " with " << remaining_waypoints.size() << " waypoint(s) left\n";
                            if (GoTo(c, waypoint, 8, 0, 1, true, false, 1.0f) == Status::Failure)
                            {
                                std::cout << "CLEAR_CUBOID skipped failed sweep waypoint " << waypoint << "\n";
                            }
                            continue;
                        }

                        if (!found_reachable)
                        {
                            const Position move_target(best_any.x, center.y, best_any.z);
                            std::cout << "CLEAR_CUBOID moving closer to " << move_target << " for target " << best_any << "\n";
                            if (GoTo(c, move_target, 4, 0, 1, true, false, 1.0f) == Status::Failure)
                            {
                                std::cout << "CLEAR_CUBOID skipped unreachable move target " << move_target << "\n";
                                skipped.push_back(best_any);
                                if (skipped.size() > 256)
                                {
                                    std::cout << "CLEAR_CUBOID stopped: too many unreachable blocks after mining " << mined << "\n";
                                    return Status::Success;
                                }
                            }
                            continue;
                        }

                        const Blockstate* target_block = current_world->GetBlock(best_reachable);
                        const std::string target_name = target_block == nullptr ? "unknown" : target_block->GetName();
                        std::cout << "CLEAR_CUBOID digging " << target_name << " at " << best_reachable
                            << " (" << (mined + 1) << "/" << max_blocks << ")\n";

                        if (Dig(c, best_reachable, true, PlayerDiggingFace::Up, false) == Status::Failure)
                        {
                            std::cout << "CLEAR_CUBOID skipped failed block " << best_reachable << "\n";
                            skipped.push_back(best_reachable);
                            if (skipped.size() > 256)
                            {
                                std::cout << "CLEAR_CUBOID stopped: too many failed blocks after mining " << mined << "\n";
                                return Status::Success;
                            }
                            continue;
                        }
                        mined += 1;
                    }

                    std::cout << "CLEAR_CUBOID stopped at per-run cap " << max_blocks << "\n";
                    return Status::Success;
                })
                .leaf([](ChatCommandClient& c) { c.SetBehaviourTree(nullptr); return Status::Success; })
            .end();

        SetBehaviourTree(tree);
    }
    else if (splitted_msg[1] == "interact")
    {
        if (splitted_msg.size() < 5)
        {
            SendChatMessage("Usage: [BotName] [interact] [x] [y] [z]");
            return;
        }
        Position pos;
        try
        {
            pos = Position(std::stoi(splitted_msg[2]), std::stoi(splitted_msg[3]), std::stoi(splitted_msg[4]));
        }
        catch (const std::invalid_argument&)
        {
            return;
        }
        catch (const std::out_of_range&)
        {
            return;
        }

        auto tree = Builder<ChatCommandClient>("interact")
            // shortcut for composite<Sequence<ChatCommandClient>>()
            .sequence()
                .succeeder().sequence()
                    .leaf("go next to block", GoTo, pos, 4, 0, 1, true, false, 1.0f)
                    // Set interaction position in the blackboard
                    .leaf(SetBlackboardData<Position>, "InteractWithBlock.pos", pos)
                    .selector()
                        // Perform action using the data in the blackboard
                        .leaf("interact with block", InteractWithBlockBlackboard)
                        // Say something if it fails
                        .leaf(Say, "Interacting failed :(")
                    .end()
                    // Remove interaction position in the blackboard because
                    // we don't want to leave a mess (and to show how to do it)
                    .leaf(RemoveBlackboardData, "InteractWithBlock.pos")
                .end()
                // Switch back to empty behaviour
                .leaf([](ChatCommandClient& c) { c.SetBehaviourTree(nullptr); return Status::Success; })
            .end();

        SetBehaviourTree(tree);
    }
    else
    {
        return;
    }
}

void ChatCommandClient::CheckPerimeter(const Position& pos, const float radius, const bool check_lighting)
{
    std::vector<Position> found_positions;

    Position current_position;
    for (int y = static_cast<int>(-radius - 1); y < radius + 1; ++y)
    {
        current_position.y = pos.y + y;
        for (int x = static_cast<int>(-radius - 1); x < radius + 1; ++x)
        {
            current_position.x = pos.x + x;
            for (int z = static_cast<int>(-radius - 1); z < radius + 1; ++z)
            {
                current_position.z = pos.z + z;

                if (x * x + y * y + z * z > radius * radius)
                {
                    continue;
                }

                const Blockstate* block = world->GetBlock(current_position);

                if (block == nullptr || !block->IsAir())
                {
                    continue;
                }

                Position adjacent_position = current_position;
                adjacent_position.y -= 1;

                const Blockstate *adjacent_block = world->GetBlock(adjacent_position);

                if (!adjacent_block ||
                    adjacent_block->IsFluid() ||
                    !adjacent_block->IsSolid() ||
                    adjacent_block->IsTransparent() ||
                    adjacent_block->GetName() == "minecraft:bedrock" ||
                    adjacent_block->GetName() == "minecraft:barrier")
                {
                    continue;
                }

                adjacent_position.y += 2;

                adjacent_block = world->GetBlock(adjacent_position);

                if (adjacent_block &&
                    (adjacent_block->IsSolid() ||
                    adjacent_block->IsFluid()))
                {
                    continue;
                }

                if (check_lighting && world->GetBlockLight(current_position) > 7)
                {
                    continue;
                }

                found_positions.push_back(current_position);
            }
        }
    }

    std::ofstream output_file("perimeter_check_" + std::to_string(pos.x) + "_" + std::to_string(pos.y) + "_" + std::to_string(pos.z) + "_radius_" + std::to_string(radius) + ".txt", std::ios::out);

    if (output_file.is_open())
    {
        for (int i = 0; i < found_positions.size(); ++i)
        {
            output_file << found_positions[i] << "\n";
        }

        output_file.close();
    }
}
