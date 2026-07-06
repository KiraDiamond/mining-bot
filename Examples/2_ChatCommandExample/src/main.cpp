#include <iostream>
#include <string>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <sstream>
#include <thread>
#include <vector>

#include "botcraft/Utilities/Logger.hpp"

#include "ChatCommandClient.hpp"

void ShowHelp(const char* argv0)
{
    std::cout << "Usage: " << argv0 << " <options>\n"
        << "Options:\n"
        << "\t-h, --help\tShow this help message\n"
        << "\t--address\tAddress of the server you want to connect to, default: 127.0.0.1:25565\n"
        << "\t--login\t\tPlayer name in offline mode, empty for Microsoft account, default: BCChatCommand\n"
        << "\t--cache-key\tMicrosoft auth cache key when --login is empty, default: empty\n"
        << "\t--command-file\tAppend one command per line here, for example: goto 22 63 -192\n"
        << std::endl;
}

struct Args
{
    bool help = false;
    std::string address = "127.0.0.1:25565";
    std::string login = "BCChatCommand";
    std::string cache_key = "";
    std::string command_file = "";

    int return_code = 0;
};

Args ParseCommandLine(int argc, char* argv[]);

int main(int argc, char* argv[])
{
    try
    {
        // Init logging, log everything >= Info, only to console, no file
        Botcraft::Logger::GetInstance().SetLogLevel(Botcraft::LogLevel::Info);
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

        ChatCommandClient client;
        client.SetAutoRespawn(true);

        LOG_INFO("Starting connection process");
        if (args.login.empty())
        {
            client.ConnectMicrosoft(args.address, args.cache_key);
        }
        else
        {
            client.Connect(args.address, args.login);
        }

        std::atomic<bool> stop_command_thread = false;
        std::thread command_thread;
        if (!args.command_file.empty())
        {
            command_thread = std::thread([&client, &args, &stop_command_thread]() {
                Botcraft::Logger::GetInstance().RegisterThread("commands");
                std::uintmax_t offset = 0;
                while (!stop_command_thread)
                {
                    try
                    {
                        if (std::filesystem::exists(args.command_file))
                        {
                            const std::uintmax_t size = std::filesystem::file_size(args.command_file);
                            if (size < offset)
                            {
                                offset = 0;
                            }
                            if (size > offset)
                            {
                                std::ifstream input(args.command_file, std::ios::in);
                                input.seekg(static_cast<std::streamoff>(offset));
                                std::string line;
                                while (std::getline(input, line))
                                {
                                    std::istringstream ss(line);
                                    const std::vector<std::string> command({
                                        std::istream_iterator<std::string>{ss},
                                        std::istream_iterator<std::string>{}
                                    });
                                    if (!command.empty())
                                    {
                                        LOG_INFO("External command: " << line);
                                        client.ProcessExternalCommand(command);
                                    }
                                }
                                offset = size;
                            }
                        }
                    }
                    catch (const std::exception& e)
                    {
                        LOG_WARNING("Command file watcher error: " << e.what());
                    }

                    std::this_thread::sleep_for(std::chrono::milliseconds(250));
                }
            });
        }

        client.RunBehaviourUntilClosed();

        stop_command_thread = true;
        if (command_thread.joinable())
        {
            command_thread.join();
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
        else if (arg == "--cache-key")
        {
            if (i + 1 < argc)
            {
                args.cache_key = argv[++i];
            }
            else
            {
                LOG_FATAL("--cache-key requires an argument");
                args.return_code = 1;
                return args;
            }
        }
        else if (arg == "--command-file")
        {
            if (i + 1 < argc)
            {
                args.command_file = argv[++i];
            }
            else
            {
                LOG_FATAL("--command-file requires an argument");
                args.return_code = 1;
                return args;
            }
        }
    }
    return args;
}
