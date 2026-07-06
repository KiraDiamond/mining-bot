#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
#pragma once
#include "protocolCraft/Types/Components/DataComponentType.hpp"

#if PROTOCOL_VERSION < 775 /* < 26.1 */
#include "protocolCraft/Types/Identifier.hpp"
#else
#include "protocolCraft/Types/HolderSet.hpp"
#endif

namespace ProtocolCraft
{
    namespace Components
    {
        class DataComponentTypeProvidesBannerPattern : public DataComponentType
        {
#if PROTOCOL_VERSION < 775 /* < 26.1 */
            SERIALIZED_FIELD(Key, Identifier);
#else
            SERIALIZED_FIELD(Keys, HolderSet);
#endif

            DECLARE_READ_WRITE_SERIALIZE;
        };
    }
}
#endif
