#if PROTOCOL_VERSION > 767 /* > 1.21.1 */
#pragma once
#include "protocolCraft/Types/Components/DataComponentType.hpp"
#include "protocolCraft/Types/Identifier.hpp"
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#include "protocolCraft/Types/HolderSet.hpp"
#endif

namespace ProtocolCraft
{
    namespace Components
    {
        class DataComponentTypeDamageResistant : public DataComponentType
        {
#if PROTOCOL_VERSION < 775 /* < 26.1 */
            SERIALIZED_FIELD(Types, Identifier);
#else
            SERIALIZED_FIELD(Types, HolderSet);
#endif

            DECLARE_READ_WRITE_SERIALIZE;
        };
    }
}
#endif
